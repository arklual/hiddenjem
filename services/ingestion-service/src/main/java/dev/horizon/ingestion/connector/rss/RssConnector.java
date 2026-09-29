package dev.horizon.ingestion.connector.rss;

import java.io.StringReader;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;

import com.rometools.rome.feed.synd.SyndCategory;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.feed.synd.SyndPerson;
import com.rometools.rome.io.FeedException;
import com.rometools.rome.io.SyndFeedInput;

import dev.horizon.ingestion.config.ConnectorsProperties;
import dev.horizon.ingestion.connector.rss.model.RssItem;
import dev.horizon.ingestion.connector.support.AbstractSourceConnector;
import dev.horizon.ingestion.connector.support.ConnectorException;
import dev.horizon.ingestion.connector.support.ConnectorHttpClient;
import dev.horizon.ingestion.connector.support.RawHttpResponse;
import dev.horizon.ingestion.connector.support.RobotsPolicy;
import dev.horizon.ingestion.connector.support.SourcePage;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.document.TextNormalization;
import dev.horizon.ingestion.domain.port.CollectionRequest;
import dev.horizon.ingestion.domain.port.DocumentNormalizer;
import dev.horizon.ingestion.domain.port.SourceDescriptor;
import dev.horizon.ingestion.domain.run.Cursor;
import dev.horizon.ingestion.domain.support.Hashing;

/**
 * Generic RSS/Atom connector for news and vendor blogs, built on Rome.
 *
 * <p><b>Paging is feed-by-feed.</b> A feed has no cursor and no pages — it is a snapshot of the last
 * few dozen items. The configured feed list is therefore walked as if it were pages: the cursor holds
 * the index of the next feed, so a run that stops halfway resumes at the right outlet and the
 * per-page transaction boundary still means something.
 *
 * <p><b>Filtering happens locally.</b> Feeds cannot be queried, so entries are matched against the
 * query terms in memory. Without that, a technology query would drag every unrelated headline of the
 * week into the corpus.
 *
 * <p>A feed that is down is skipped rather than fatal: with several outlets configured, losing one is
 * a partial result, not a failed run (BR-C7).
 */
public class RssConnector extends AbstractSourceConnector<RssItem> {

    public static final String SOURCE_ID = "rss";
    private static final Logger log = LoggerFactory.getLogger(RssConnector.class);
    private static final int MIN_TERM_LENGTH = 3;
    private static final int MAX_EXTERNAL_ID_LENGTH = 200;

    private final ConnectorHttpClient http;
    private final RssNormalizer normalizer = new RssNormalizer();
    private final List<String> feeds;
    private final int requestsPerMinute;
    private final boolean enabled;
    private final RobotsPolicy robots;
    private final String userAgent;

    public RssConnector(ConnectorsProperties properties, ConnectorHttpClient http) {
        var settings = properties.settings(SOURCE_ID);
        this.http = http;
        this.feeds = List.copyOf(settings.feeds());
        this.requestsPerMinute = settings.requestsPerMinuteOr(30);
        this.enabled = settings.enabledOr(true);
        this.userAgent = properties.fullUserAgent();
        // Единственный коннектор, ходящий по произвольным адресам: остальные обращаются к
        // документированным API, чьи условия соблюдаются контактным User-Agent и лимитом запросов
        // (BR-C3). robots.txt написан про обход сайтов, и здесь он к месту.
        this.robots = new RobotsPolicy(uri -> {
            try {
                return http.get(SOURCE_ID, uri, Map.of(), requestsPerMinute).body();
            } catch (RuntimeException e) {
                return null;
            }
        });
    }

    @Override
    public SourceDescriptor descriptor() {
        var descriptor = new SourceDescriptor(
                SOURCE_ID,
                "RSS / Atom",
                SourceClass.NEWS,
                Set.of(SourceClass.NEWS),
                requestsPerMinute,
                false,
                true,
                null,
                false);
        if (!enabled) {
            return descriptor.switchedOff("Коннектор rss выключен конфигурацией");
        }
        if (feeds.isEmpty()) {
            return descriptor.unavailable("Не настроено ни одной ленты (horizon.connectors.sources.rss.feeds)");
        }
        return descriptor;
    }

    @Override
    public boolean supports(CollectionRequest request) {
        return descriptor().available() && request.accepts(SourceClass.NEWS);
    }

    @Override
    protected DocumentNormalizer<RssItem> normalizer() {
        return normalizer;
    }

    @Override
    protected SourcePage<RssItem> fetchPage(CollectionRequest request, Cursor cursor) {
        if (feeds.isEmpty()) {
            return SourcePage.empty();
        }
        // An index left over from a run against a longer feed list restarts at the first feed rather
        // than reporting an empty source.
        int index = indexOf(cursor);
        if (index >= feeds.size()) {
            index = 0;
        }
        String feedUrl = feeds.get(index);
        Cursor next = Cursor.ofValue(Integer.toString(index + 1));
        boolean last = index + 1 >= feeds.size();

        if (!robots.allows(URI.create(feedUrl), userAgent)) {
            // Пропуск, а не отказ прогона: запрет одной площадки не должен стоить нам остальных, и
            // он не является неисправностью — это её решение, которое мы обязаны уважать.
            log.info("Feed {} is disallowed by robots.txt, skipping", feedUrl);
            return new SourcePage<>(List.of(), next, last);
        }

        List<RssItem> items;
        try {
            items = readFeed(feedUrl, request);
        } catch (ConnectorException e) {
            // One dead outlet must not cost us the others.
            log.warn("Feed {} is unavailable: {}", feedUrl, e.getMessage());
            items = List.of();
        }
        return new SourcePage<>(items, next, last);
    }

    private List<RssItem> readFeed(String feedUrl, CollectionRequest request) {
        RawHttpResponse response = http.get(
                SOURCE_ID,
                URI.create(feedUrl),
                Map.of(
                        HttpHeaders.ACCEPT,
                        "application/rss+xml, application/atom+xml, application/xml;q=0.9, */*;q=0.8"),
                requestsPerMinute);
        SyndFeed feed = parse(feedUrl, response.body());
        List<String> terms = request.isWildcard() ? List.of() : matchableTerms(request);
        String feedTitle = feed.getTitle() == null ? feedUrl : feed.getTitle().trim();

        List<RssItem> items = new ArrayList<>();
        for (SyndEntry entry : feed.getEntries()) {
            Instant publishedAt = instantOf(entry);
            if (publishedAt == null) {
                continue;
            }
            var item = new RssItem(
                    feedUrl,
                    feedTitle,
                    entry.getTitle(),
                    linkOf(entry, feedUrl),
                    descriptionOf(entry),
                    publishedAt,
                    authorsOf(entry),
                    categoriesOf(entry),
                    SOURCE_ID,
                    externalIdOf(entry, feedUrl),
                    response.provenance());
            if (matches(item, request, terms)) {
                items.add(item);
            }
        }
        return items;
    }

    private SyndFeed parse(String feedUrl, String body) {
        SyndFeedInput input = new SyndFeedInput();
        // Feeds are untrusted XML from the open internet: no DTDs, no entity expansion.
        input.setAllowDoctypes(false);
        input.setXmlHealerOn(true);
        try {
            return input.build(new StringReader(body));
        } catch (FeedException | IllegalArgumentException e) {
            throw new ConnectorException.Permanent(SOURCE_ID, 200, "Cannot parse feed " + feedUrl, e);
        }
    }

    private boolean matches(RssItem item, CollectionRequest request, List<String> terms) {
        if (!request.withinWindow(java.time.LocalDate.ofInstant(item.publishedAt(), java.time.ZoneOffset.UTC))) {
            return false;
        }
        if (terms.isEmpty()) {
            return true;
        }
        String haystack = TextNormalization.normalizeTitle((item.title() == null ? "" : item.title()) + " "
                + (item.description() == null ? "" : item.description()));
        for (String term : terms) {
            if (haystack.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> matchableTerms(CollectionRequest request) {
        return request.terms().stream()
                .map(TextNormalization::normalizeTitle)
                .filter(term -> term.length() >= MIN_TERM_LENGTH)
                .toList();
    }

    private static Instant instantOf(SyndEntry entry) {
        Date published = entry.getPublishedDate() != null ? entry.getPublishedDate() : entry.getUpdatedDate();
        return published == null ? null : published.toInstant();
    }

    private static String linkOf(SyndEntry entry, String feedUrl) {
        if (entry.getLink() != null && !entry.getLink().isBlank()) {
            return entry.getLink().trim();
        }
        return entry.getUri() != null && !entry.getUri().isBlank()
                ? entry.getUri().trim()
                : feedUrl;
    }

    private static String descriptionOf(SyndEntry entry) {
        if (entry.getDescription() != null && entry.getDescription().getValue() != null) {
            return entry.getDescription().getValue();
        }
        if (!entry.getContents().isEmpty()) {
            return entry.getContents().get(0).getValue();
        }
        return null;
    }

    private static List<String> authorsOf(SyndEntry entry) {
        List<String> authors = new ArrayList<>();
        for (SyndPerson person : entry.getAuthors()) {
            if (person.getName() != null && !person.getName().isBlank()) {
                authors.add(person.getName().trim());
            }
        }
        if (authors.isEmpty() && entry.getAuthor() != null && !entry.getAuthor().isBlank()) {
            authors.add(entry.getAuthor().trim());
        }
        return authors;
    }

    private static List<String> categoriesOf(SyndEntry entry) {
        List<String> categories = new ArrayList<>();
        for (SyndCategory category : entry.getCategories()) {
            if (category.getName() != null && !category.getName().isBlank()) {
                categories.add(category.getName().trim());
            }
        }
        return categories;
    }

    /**
     * Identity of a feed entry: its GUID or link, hashed when it exceeds the 200-character column.
     *
     * <p>Hashing rather than truncating: two long URLs that share a prefix would truncate to the same
     * id and one article would silently overwrite the other.
     */
    static String externalIdOf(SyndEntry entry, String feedUrl) {
        String candidate = entry.getUri() != null && !entry.getUri().isBlank() ? entry.getUri() : entry.getLink();
        if (candidate == null || candidate.isBlank()) {
            candidate = feedUrl + "#" + (entry.getTitle() == null ? "" : entry.getTitle());
        }
        String trimmed = candidate.trim();
        return trimmed.length() <= MAX_EXTERNAL_ID_LENGTH ? trimmed : "sha256:" + Hashing.sha256Hex(trimmed);
    }

    private static int indexOf(Cursor cursor) {
        if (cursor == null || cursor.value() == null) {
            return 0;
        }
        try {
            return Math.max(Integer.parseInt(cursor.value()), 0);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
