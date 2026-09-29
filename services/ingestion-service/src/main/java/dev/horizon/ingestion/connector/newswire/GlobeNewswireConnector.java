package dev.horizon.ingestion.connector.newswire;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.http.HttpHeaders;
import org.springframework.web.util.UriComponentsBuilder;

import dev.horizon.ingestion.config.ConnectorsProperties;
import dev.horizon.ingestion.connector.newswire.model.PressRelease;
import dev.horizon.ingestion.connector.support.AbstractSourceConnector;
import dev.horizon.ingestion.connector.support.ConnectorException;
import dev.horizon.ingestion.connector.support.ConnectorHttpClient;
import dev.horizon.ingestion.connector.support.CrawlDelay;
import dev.horizon.ingestion.connector.support.RawHttpResponse;
import dev.horizon.ingestion.connector.support.RobotsPolicy;
import dev.horizon.ingestion.connector.support.SearchPhrases;
import dev.horizon.ingestion.connector.support.SourcePage;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.port.CollectionRequest;
import dev.horizon.ingestion.domain.port.DocumentNormalizer;
import dev.horizon.ingestion.domain.port.SourceDescriptor;
import dev.horizon.ingestion.domain.run.Cursor;

/**
 * GlobeNewswire — пресс-релизы компаний через ленту по ключевому слову.
 *
 * <p>Заведён ради рыночной стадии эталона: 80 из 100 сигналов не имеют научных ссылок, и первое их
 * публичное появление — объявление компании о продукте, партнёрстве, запуске (разбор эталона).
 * Релиз — свидетельство слабое (его пишет сама компания), но датированное точно и называющее
 * компанию; модуль доверенности аналитики понижает вес площадок распространения релизов сам.
 *
 * <p><b>Спрашивается лента {@code /RssFeed/keyword/<фраза>}</b>: RSS 2.0 с двадцатью последними
 * релизами, помеченными этим ключевым словом, — с датой, выпустившей компанией
 * ({@code dc:contributor}), языком и анонсом. Страниц у неё нет, поэтому глубина — число
 * формулировок: каждая цель направления спрашивается отдельно. Двадцать последних релизов — это
 * недели для ходовой темы («edge computing») и год-полтора для узкой («stablecoin payments»):
 * глубже в историю источник не заглядывает, и это надо помнить, сравнивая годы.
 *
 * <p><b>Что можно и чего нельзя.</b> {@code robots.txt} GlobeNewswire запрещает всем HTML-поиск
 * ({@code /search/}), ленты отделов новостей ({@code /newsroom/rss/}), {@code /News/Index} и
 * {@code /api/}; ленту по ключевому слову не запрещает, ИИ-краулеров не называет.
 * {@code Crawl-delay} там нет — держим паузу в три секунды на хост сами. Проверка — перед
 * обращением; запрет — отказ источника с причиной, а не пустой корпус.
 *
 * <p><b>Ноль и отказ различаются.</b> Ответ, который не RSS, роняет прогон источника; лента без
 * записей — законный ноль.
 */
public class GlobeNewswireConnector extends AbstractSourceConnector<PressRelease> {

    public static final String SOURCE_ID = "globenewswire";
    static final String WIRE = "GlobeNewswire";
    private static final String DEFAULT_BASE_URL = "https://www.globenewswire.com/RssFeed/keyword/{q}";
    private static final int MIN_PHRASE_LENGTH = 3;
    private static final Pattern PRE_BLOCK = Pattern.compile("(?is)<pre>.*?</pre>");

    private final ConnectorHttpClient http;
    private final PressReleaseNormalizer normalizer = new PressReleaseNormalizer();
    private final String baseUrl;
    private final int requestsPerMinute;
    private final boolean enabled;
    private final RobotsPolicy robots;
    private final String userAgent;
    private final CrawlDelay crawlDelay;

    public GlobeNewswireConnector(ConnectorsProperties properties, ConnectorHttpClient http) {
        this(properties, http, new CrawlDelay(Duration.ofSeconds(3)));
    }

    /** Для тестов: пауза подменяется, чтобы разбор записанной ленты не ждал. */
    GlobeNewswireConnector(ConnectorsProperties properties, ConnectorHttpClient http, CrawlDelay crawlDelay) {
        var settings = properties.settings(SOURCE_ID);
        this.http = http;
        this.crawlDelay = crawlDelay;
        this.baseUrl = settings.baseUrlOr(DEFAULT_BASE_URL);
        this.requestsPerMinute = settings.requestsPerMinuteOr(20);
        this.enabled = settings.enabledOr(true);
        this.userAgent = properties.fullUserAgent();
        this.robots = new RobotsPolicy(uri -> {
            try {
                crawlDelay.await(uri.getHost());
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
                "GlobeNewswire (пресс-релизы)",
                SourceClass.NEWS,
                Set.of(SourceClass.NEWS),
                requestsPerMinute,
                false,
                true,
                null,
                false);
        return enabled ? descriptor : descriptor.switchedOff("Коннектор globenewswire выключен конфигурацией");
    }

    @Override
    public boolean supports(CollectionRequest request) {
        return enabled && !request.isWildcard() && request.accepts(SourceClass.NEWS);
    }

    @Override
    protected DocumentNormalizer<PressRelease> normalizer() {
        return normalizer;
    }

    /** Английские цели направления; русскую формулировку лента не понимает. */
    static List<String> phrases(CollectionRequest request) {
        return SearchPhrases.of(request.upstreamTerms(), MIN_PHRASE_LENGTH).stream()
                // Косая черта в пути разбила бы ключевое слово на два сегмента адреса.
                .map(phrase -> phrase.replace('/', ' ').trim())
                .filter(phrase -> !phrase.isEmpty())
                .distinct()
                .toList();
    }

    URI feedUri(String phrase) {
        return UriComponentsBuilder.fromUriString(baseUrl)
                .buildAndExpand(phrase)
                .encode()
                .toUri();
    }

    @Override
    protected SourcePage<PressRelease> fetchPage(CollectionRequest request, Cursor cursor) {
        List<String> phrases = phrases(request);
        int index = indexOf(cursor);
        if (index >= phrases.size()) {
            return SourcePage.empty();
        }
        URI uri = feedUri(phrases.get(index));
        if (!robots.allows(uri, userAgent)) {
            throw new ConnectorException.Permanent(
                    SOURCE_ID, 0, "robots.txt GlobeNewswire запрещает " + uri.getRawPath());
        }
        crawlDelay.await(uri.getHost());
        RawHttpResponse response = http.get(
                SOURCE_ID,
                uri,
                Map.of(HttpHeaders.ACCEPT, "application/rss+xml, application/xml;q=0.9, */*;q=0.8"),
                requestsPerMinute);
        List<PressRelease> items = new ArrayList<>();
        for (GlobeNewswireFeed.Item item : GlobeNewswireFeed.parse(SOURCE_ID, uri.toString(), response.body())) {
            if (item.publishedAt() == null || item.link() == null || item.title() == null) {
                continue;
            }
            LocalDate published = LocalDate.ofInstant(item.publishedAt(), ZoneOffset.UTC);
            if (!request.withinWindow(published)) {
                continue;
            }
            items.add(new PressRelease(
                    SOURCE_ID,
                    item.identifier(),
                    WIRE,
                    item.title(),
                    item.link(),
                    published,
                    summary(item.description()),
                    item.contributor(),
                    item.language(),
                    response.provenance()));
        }
        boolean last = index + 1 >= phrases.size();
        return new SourcePage<>(items, Cursor.ofValue(Integer.toString(index + 1)), last);
    }

    /**
     * Анонс без повтора: GlobeNewswire кладёт тот же абзац второй раз внутрь {@code <pre>}. Если
     * кроме {@code <pre>} ничего нет — берётся он.
     */
    static String summary(String description) {
        if (description == null) {
            return null;
        }
        String outside = HtmlText.plain(PRE_BLOCK.matcher(description).replaceAll(" "));
        return outside != null ? outside : HtmlText.plain(description);
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
