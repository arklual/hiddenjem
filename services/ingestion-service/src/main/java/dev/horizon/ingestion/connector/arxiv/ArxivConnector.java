package dev.horizon.ingestion.connector.arxiv;

import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.util.UriComponentsBuilder;

import dev.horizon.ingestion.config.ConnectorsProperties;
import dev.horizon.ingestion.connector.arxiv.model.ArxivEntry;
import dev.horizon.ingestion.connector.support.AbstractSourceConnector;
import dev.horizon.ingestion.connector.support.ConnectorHttpClient;
import dev.horizon.ingestion.connector.support.RawHttpResponse;
import dev.horizon.ingestion.connector.support.SourcePage;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.port.CollectionRequest;
import dev.horizon.ingestion.domain.port.DocumentNormalizer;
import dev.horizon.ingestion.domain.port.SourceDescriptor;
import dev.horizon.ingestion.domain.run.Cursor;

/**
 * arXiv Atom API — the preprint source, and the earliest signal in the corpus.
 *
 * <p><b>Rate limit: 1 request per 3 seconds</b> (20/minute, burst 1), which is what arXiv's terms
 * ask for. It is the slowest connector by an order of magnitude and that is intentional: exceeding
 * it gets the platform blocked, and a slow source is infinitely better than a banned one.
 *
 * <p><b>Paging: {@code start} / {@code max_results} offsets.</b> arXiv has no cursor; the offset is
 * carried in the {@link Cursor} value and the feed's own {@code opensearch:totalResults} tells us
 * when to stop. Results are sorted by submission date descending so that an interrupted crawl has
 * already seen the newest — the part that matters for emergence.
 */
public class ArxivConnector extends AbstractSourceConnector<ArxivEntry> {

    public static final String SOURCE_ID = "arxiv";
    /**
     * Адрес API. Именно https: по http arXiv отвечает 301, а редиректы клиент не следует —
     * коннектор получал пустое тело и объявлял «arXiv returned unparsable XML». Источник числился
     * включённым и не отдавал ни одного документа, а причина выглядела как поломка разбора.
     */
    private static final String DEFAULT_BASE_URL = "https://export.arxiv.org/api/query";

    private static final DateTimeFormatter ARXIV_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final ConnectorHttpClient http;
    private final ArxivFeedParser parser = new ArxivFeedParser();
    private final ArxivNormalizer normalizer = new ArxivNormalizer();
    private final String baseUrl;
    private final int pageSize;
    private final int requestsPerMinute;
    private final boolean enabled;
    private final String defaultQuery;

    public ArxivConnector(ConnectorsProperties properties, ConnectorHttpClient http) {
        var settings = properties.settings(SOURCE_ID);
        this.http = http;
        this.baseUrl = settings.baseUrlOr(DEFAULT_BASE_URL);
        this.pageSize = Math.min(settings.pageSizeOr(100), 2000);
        this.requestsPerMinute = settings.requestsPerMinuteOr(20);
        this.enabled = settings.enabledOr(true);
        this.defaultQuery = settings.defaultQueryOr("cat:cs.AI OR cat:cs.LG OR cat:stat.ML");
    }

    @Override
    public SourceDescriptor descriptor() {
        var descriptor = new SourceDescriptor(
                SOURCE_ID,
                "arXiv",
                SourceClass.PREPRINT,
                Set.of(SourceClass.PREPRINT),
                requestsPerMinute,
                false,
                true,
                null,
                false);
        return enabled ? descriptor : descriptor.switchedOff("Коннектор arxiv выключен конфигурацией");
    }

    @Override
    public boolean supports(CollectionRequest request) {
        return enabled && request.accepts(SourceClass.PREPRINT);
    }

    @Override
    protected DocumentNormalizer<ArxivEntry> normalizer() {
        return normalizer;
    }

    @Override
    protected SourcePage<ArxivEntry> fetchPage(CollectionRequest request, Cursor cursor) {
        int start = offsetOf(cursor);
        URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                .queryParam("search_query", searchQuery(request))
                .queryParam("start", start)
                .queryParam("max_results", pageSize)
                .queryParam("sortBy", "submittedDate")
                .queryParam("sortOrder", "descending")
                .build()
                .encode()
                .toUri();

        RawHttpResponse response = http.get(
                SOURCE_ID, uri, Map.of(HttpHeaders.ACCEPT, MediaType.APPLICATION_ATOM_XML_VALUE), requestsPerMinute);
        ArxivFeedParser.Feed feed = parser.parse(SOURCE_ID, response.body(), response.provenance());

        int next = start + Math.max(feed.entries().size(), 1);
        boolean last = feed.entries().isEmpty()
                || (feed.totalResults() >= 0 && next >= feed.totalResults())
                || feed.entries().size() < pageSize;
        return new SourcePage<>(feed.entries(), Cursor.ofValue(Integer.toString(next)), last);
    }

    /**
     * Builds arXiv's query language: a free-text clause (or the configured default for scheduled
     * crawls) intersected with the submission-date window.
     */
    private String searchQuery(CollectionRequest request) {
        // Каждая цель — отдельная фраза, соединённая через ИЛИ: arXiv ищет `all:"…"` целиком, и
        // склеенные в одну строку четыре кода не находят ничего.
        String terms = request.isWildcard()
                ? defaultQuery
                : request.upstreamTerms().stream()
                        .map(term -> "all:\"%s\"".formatted(escape(term)))
                        .collect(java.util.stream.Collectors.joining(" OR "));
        String from = ARXIV_DATE.format(request.windowFrom());
        String to = ARXIV_DATE.format(request.windowTo());
        return "(%s) AND submittedDate:[%s0000 TO %s2359]".formatted(terms, from, to);
    }

    private static String escape(String query) {
        return query.replace("\"", " ").trim();
    }

    private static int offsetOf(Cursor cursor) {
        if (cursor == null || cursor.value() == null) {
            return 0;
        }
        try {
            return Math.max(Integer.parseInt(cursor.value()), 0);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Exposed for the contract test, which asserts the exact query arXiv receives. */
    public List<String> describeQuery(CollectionRequest request) {
        return List.of(searchQuery(request), Integer.toString(pageSize));
    }

    /** Window bounds are inclusive; kept here so the formatter is used in one place only. */
    static String formatDate(LocalDate date) {
        return ARXIV_DATE.format(date);
    }
}
