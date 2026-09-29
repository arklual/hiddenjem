package dev.horizon.ingestion.connector.crossref;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizon.ingestion.config.ConnectorsProperties;
import dev.horizon.ingestion.connector.crossref.model.CrossrefResponse;
import dev.horizon.ingestion.connector.support.AbstractSourceConnector;
import dev.horizon.ingestion.connector.support.ConnectorHttpClient;
import dev.horizon.ingestion.connector.support.JsonBodies;
import dev.horizon.ingestion.connector.support.RawHttpResponse;
import dev.horizon.ingestion.connector.support.SourcePage;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.port.CollectionRequest;
import dev.horizon.ingestion.domain.port.DocumentNormalizer;
import dev.horizon.ingestion.domain.port.SourceDescriptor;
import dev.horizon.ingestion.domain.run.Cursor;

/**
 * Crossref {@code /works} — DOI registration metadata, the authority for peer-reviewed literature.
 *
 * <p><b>Paging: deep cursor.</b> Crossref caps offset paging at 10 000 rows; beyond that only
 * {@code cursor=*} with {@code message.next-cursor} works. Since a broad technology query easily
 * matches more than that, the cursor is not an optimisation here but the only correct option.
 *
 * <p><b>{@code mailto} in the User-Agent.</b> Crossref explicitly asks callers to identify
 * themselves and routes them to the polite pool; anonymous traffic is throttled first when the API
 * is under load (BR-C3, NFR-S12). The address is sent in the User-Agent, which is Crossref's
 * documented preference, and repeated as a query parameter for the request log.
 */
public class CrossrefConnector extends AbstractSourceConnector<CrossrefResponse.Raw> {

    public static final String SOURCE_ID = "crossref";
    private static final String DEFAULT_BASE_URL = "https://api.crossref.org/works";
    private static final String CURSOR_START = "*";

    private final ConnectorHttpClient http;
    private final ObjectMapper objectMapper;
    private final CrossrefNormalizer normalizer = new CrossrefNormalizer();
    private final String baseUrl;
    private final String userAgent;
    private final String mailto;
    private final int pageSize;
    private final int requestsPerMinute;
    private final boolean enabled;

    public CrossrefConnector(ConnectorsProperties properties, ConnectorHttpClient http, ObjectMapper objectMapper) {
        var settings = properties.settings(SOURCE_ID);
        this.http = http;
        this.objectMapper = objectMapper;
        this.baseUrl = settings.baseUrlOr(DEFAULT_BASE_URL);
        this.userAgent = properties.fullUserAgent();
        this.mailto = properties.contactEmail();
        this.pageSize = Math.min(settings.pageSizeOr(100), 1000);
        this.requestsPerMinute = settings.requestsPerMinuteOr(50);
        this.enabled = settings.enabledOr(true);
    }

    @Override
    public SourceDescriptor descriptor() {
        var descriptor = new SourceDescriptor(
                SOURCE_ID,
                "Crossref",
                SourceClass.JOURNAL_ARTICLE,
                Set.of(
                        SourceClass.JOURNAL_ARTICLE,
                        SourceClass.PREPRINT,
                        SourceClass.STANDARD,
                        SourceClass.ANALYST_REPORT),
                requestsPerMinute,
                false,
                true,
                null,
                false);
        return enabled ? descriptor : descriptor.switchedOff("Коннектор crossref выключен конфигурацией");
    }

    @Override
    public boolean supports(CollectionRequest request) {
        return enabled && request.acceptsAnyOf(descriptor().providedClasses());
    }

    @Override
    protected DocumentNormalizer<CrossrefResponse.Raw> normalizer() {
        return normalizer;
    }

    @Override
    protected SourcePage<CrossrefResponse.Raw> fetchPage(CollectionRequest request, Cursor cursor) {
        String cursorValue = cursor == null || cursor.value() == null ? CURSOR_START : cursor.value();
        var uriBuilder = UriComponentsBuilder.fromUriString(baseUrl)
                .queryParam("filter", filter(request))
                .queryParam("rows", pageSize)
                .queryParam("cursor", cursorValue)
                .queryParam("mailto", mailto);
        if (!request.isWildcard()) {
            uriBuilder.queryParam("query.bibliographic", request.upstreamQuery());
        }
        URI uri = uriBuilder.build().encode().toUri();

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
        headers.put(HttpHeaders.USER_AGENT, userAgent);

        RawHttpResponse response = http.get(SOURCE_ID, uri, headers, requestsPerMinute);
        CrossrefResponse body = JsonBodies.parse(objectMapper, SOURCE_ID, response.body(), CrossrefResponse.class);
        var message = body.message();
        List<CrossrefResponse.Item> items = message == null ? List.of() : message.items();

        List<CrossrefResponse.Raw> page = new ArrayList<>(items.size());
        for (CrossrefResponse.Item item : items) {
            if (item.doi() == null || item.doi().isBlank()) {
                continue; // a Crossref record without a DOI has no identity worth storing
            }
            page.add(new CrossrefResponse.Raw(item, SOURCE_ID, item.doi(), response.provenance()));
        }
        String nextCursor = message == null ? null : message.nextCursor();
        boolean last = nextCursor == null || nextCursor.isBlank() || items.isEmpty() || items.size() < pageSize;
        return new SourcePage<>(page, Cursor.ofValue(last ? cursorValue : nextCursor), last);
    }

    private static String filter(CollectionRequest request) {
        return "from-pub-date:%s,until-pub-date:%s".formatted(request.windowFrom(), request.windowTo());
    }
}
