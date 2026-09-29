package dev.horizon.ingestion.connector.lens;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizon.ingestion.config.ConnectorsProperties;
import dev.horizon.ingestion.connector.lens.model.LensScholarlyResponse;
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
 * The Lens — научные работы.
 *
 * <p>Из Lens выгружен эталонный набор кейса, поэтому источник нужен рядом с OpenAlex и Semantic
 * Scholar: тот же указатель, что у экспертов, с аффилиациями авторов и DOI. Поиск — по названию и
 * аннотации, в окне дат публикации запроса.
 *
 * <p><b>Только с ключом.</b> API Lens без ключа не отвечает; ключ выдаётся на странице подписок
 * Lens и задаётся {@code HORIZON_LENS_API_TOKEN}. Без него источник объявляет себя недоступным, а
 * не падает посреди сбора.
 */
public class LensScholarlyConnector extends AbstractSourceConnector<LensScholarlyResponse.Raw> {

    public static final String SOURCE_ID = "lens";
    private static final String DEFAULT_BASE_URL = "https://api.lens.org/scholarly/search";
    private static final List<String> FIELDS = List.of("title", "abstract");
    private static final List<String> INCLUDE = List.of(
            "lens_id", "title", "abstract", "date_published", "year_published", "publication_type", "source",
            "authors", "external_ids", "scholarly_citations_count", "languages");

    private final ConnectorHttpClient http;
    private final ObjectMapper objectMapper;
    private final LensScholarlyNormalizer normalizer = new LensScholarlyNormalizer();
    private final String baseUrl;
    private final String token;
    private final int requestsPerMinute;
    private final int pageSize;
    private final boolean enabled;

    public LensScholarlyConnector(ConnectorsProperties properties, ConnectorHttpClient http, ObjectMapper objectMapper) {
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
                "The Lens: научные работы",
                SourceClass.JOURNAL_ARTICLE,
                Set.of(SourceClass.JOURNAL_ARTICLE, SourceClass.PREPRINT),
                requestsPerMinute,
                true,
                !token.isEmpty(),
                null,
                false);
        if (!enabled) {
            return descriptor.switchedOff("Источник Lens выключен (HORIZON_SOURCE_LENS_ENABLED)");
        }
        // Без ключа источник не настроен, а не сломан: «недоступный» попадал бы в каждый отчёт
        // строкой «не ответили» и помечал его неполным, хотя спрашивать было нечем.
        return token.isEmpty() ? descriptor.switchedOff("Не задан HORIZON_LENS_API_TOKEN") : descriptor;
    }

    @Override
    public boolean supports(CollectionRequest request) {
        return descriptor().available()
                && !request.isWildcard()
                && request.acceptsAnyOf(Set.of(SourceClass.JOURNAL_ARTICLE, SourceClass.PREPRINT));
    }

    @Override
    protected DocumentNormalizer<LensScholarlyResponse.Raw> normalizer() {
        return normalizer;
    }

    @Override
    protected SourcePage<LensScholarlyResponse.Raw> fetchPage(CollectionRequest request, Cursor cursor) {
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
        LensScholarlyResponse parsed =
                JsonBodies.parse(objectMapper, SOURCE_ID, response.body(), LensScholarlyResponse.class);

        List<LensScholarlyResponse.Raw> items = new ArrayList<>(parsed.data().size());
        for (LensScholarlyResponse.Work work : parsed.data()) {
            if (work.lensId() == null || work.lensId().isBlank()) {
                continue;
            }
            items.add(new LensScholarlyResponse.Raw(work, SOURCE_ID, work.lensId(), response.provenance()));
        }
        String next = LensSearch.next(offset, size, parsed.data().size(), parsed.total());
        return new SourcePage<>(items, Cursor.ofValue(next), next == null);
    }
}
