package dev.horizon.ingestion.connector.lens;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizon.ingestion.config.ConnectorsProperties;
import dev.horizon.ingestion.connector.lens.model.LensPatentResponse;
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
 * The Lens — патенты всех ведомств.
 *
 * <p>USPTO даёт только США; Lens собирает заявки и патенты EP, WO, CN, JP, KR и других ведомств в
 * одной выдаче, а из него выгружен эталонный набор кейса. Поиск — по названию, реферату и формуле
 * изобретения, в окне дат публикации запроса.
 *
 * <p><b>Только с ключом.</b> Патентный API Lens — отдельная подписка; ключ задаётся
 * {@code HORIZON_LENS_PATENT_API_TOKEN}, а если его нет — общим {@code HORIZON_LENS_API_TOKEN}.
 */
public class LensPatentConnector extends AbstractSourceConnector<LensPatentResponse.Raw> {

    public static final String SOURCE_ID = "lenspatents";
    private static final String DEFAULT_BASE_URL = "https://api.lens.org/patent/search";
    private static final List<String> FIELDS = List.of("title", "abstract", "claims");
    private static final List<String> INCLUDE = List.of(
            "lens_id", "jurisdiction", "doc_number", "kind", "date_published", "publication_type", "lang",
            "biblio.invention_title", "biblio.parties.applicants", "biblio.parties.inventors",
            "biblio.classifications_cpc", "abstract");

    private final ConnectorHttpClient http;
    private final ObjectMapper objectMapper;
    private final LensPatentNormalizer normalizer = new LensPatentNormalizer();
    private final String baseUrl;
    private final String token;
    private final int requestsPerMinute;
    private final int pageSize;
    private final boolean enabled;

    public LensPatentConnector(ConnectorsProperties properties, ConnectorHttpClient http, ObjectMapper objectMapper) {
        var settings = properties.settings(SOURCE_ID);
        this.http = http;
        this.objectMapper = objectMapper;
        this.baseUrl = settings.baseUrlOr(DEFAULT_BASE_URL);
        this.token = settings.apiKey() == null ? "" : settings.apiKey().trim();
        this.requestsPerMinute = settings.requestsPerMinuteOr(10);
        this.pageSize = settings.pageSizeOr(100);
        this.enabled = settings.enabledOr(true);
    }

    @Override
    public SourceDescriptor descriptor() {
        var descriptor = new SourceDescriptor(
                SOURCE_ID,
                "The Lens: патенты",
                SourceClass.PATENT,
                Set.of(SourceClass.PATENT),
                requestsPerMinute,
                true,
                !token.isEmpty(),
                null,
                false);
        if (!enabled) {
            return descriptor.switchedOff("Источник патентов Lens выключен (HORIZON_SOURCE_LENS_ENABLED)");
        }
        // Без ключа источник не настроен, а не сломан: «недоступный» попадал бы в каждый отчёт
        // строкой «не ответили» и помечал его неполным, хотя спрашивать было нечем.
        return token.isEmpty() ? descriptor.switchedOff("Не задан HORIZON_LENS_PATENT_API_TOKEN или HORIZON_LENS_API_TOKEN") : descriptor;
    }

    @Override
    public boolean supports(CollectionRequest request) {
        return descriptor().available() && !request.isWildcard() && request.accepts(SourceClass.PATENT);
    }

    @Override
    protected DocumentNormalizer<LensPatentResponse.Raw> normalizer() {
        return normalizer;
    }

    @Override
    protected SourcePage<LensPatentResponse.Raw> fetchPage(CollectionRequest request, Cursor cursor) {
        String query = LensSearch.queryString(request.upstreamTerms());
        int offset = LensSearch.offset(cursor);
        int size = LensSearch.size(offset, pageSize);
        if (query.isBlank() || size == 0) {
            return new SourcePage<>(List.of(), Cursor.ofValue(null), true);
        }
        String body = LensSearch.body(
                objectMapper, query, FIELDS, request.windowFrom(), request.windowTo(), offset, size, INCLUDE);
        RawHttpResponse response =
                http.postJson(SOURCE_ID, URI.create(baseUrl), LensSearch.headers(token), body, requestsPerMinute);
        LensPatentResponse parsed = JsonBodies.parse(objectMapper, SOURCE_ID, response.body(), LensPatentResponse.class);

        List<LensPatentResponse.Raw> items = new ArrayList<>(parsed.data().size());
        for (LensPatentResponse.Patent patent : parsed.data()) {
            if (patent.lensId() == null || patent.lensId().isBlank()) {
                continue;
            }
            items.add(new LensPatentResponse.Raw(patent, SOURCE_ID, patent.lensId(), response.provenance()));
        }
        String next = LensSearch.next(offset, size, parsed.data().size(), parsed.total());
        return new SourcePage<>(items, Cursor.ofValue(next), next == null);
    }
}
