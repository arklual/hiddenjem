package dev.horizon.ingestion.connector.semanticscholar;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizon.ingestion.config.ConnectorsProperties;
import dev.horizon.ingestion.connector.semanticscholar.model.SemanticScholarResponse;
import dev.horizon.ingestion.connector.support.AbstractSourceConnector;
import dev.horizon.ingestion.connector.support.ConnectorHttpClient;
import dev.horizon.ingestion.connector.support.JsonBodies;
import dev.horizon.ingestion.connector.support.RawHttpResponse;
import dev.horizon.ingestion.connector.support.SearchPhrases;
import dev.horizon.ingestion.connector.support.SourcePage;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.port.CollectionRequest;
import dev.horizon.ingestion.domain.port.DocumentNormalizer;
import dev.horizon.ingestion.domain.port.SourceDescriptor;
import dev.horizon.ingestion.domain.run.Cursor;

/**
 * Semantic Scholar — второй научный указатель рядом с OpenAlex.
 *
 * <p>Назван в обоих исследованиях к кейсу («рост, impact»; «научные публикации»). Отличие от OpenAlex, ради которого он нужен: собственный указатель цитирований и быстрая
 * индексация препринтов — работа с arXiv попадает сюда с аннотацией и числом цитирований, которых
 * у самого arXiv нет.
 *
 * <p><b>Массовый поиск, а не обычный.</b> {@code /paper/search} отдаёт не больше тысячи работ по
 * сто за раз; {@code /paper/search/bulk} — по тысяче за запрос с продолжением по токену. Цели
 * направления соединяются через {@code |} — синтаксис ИЛИ этого поиска.
 *
 * <p><b>Без ключа.</b> Анонимные запросы делят общий пул, и при нагрузке источник отвечает
 * {@code 429}: это обычный отказ источника, отчёт помечается неполным. Ключ задаётся
 * {@code HORIZON_SEMANTIC_SCHOLAR_API_KEY} и уходит заголовком {@code x-api-key}.
 */
public class SemanticScholarConnector extends AbstractSourceConnector<SemanticScholarResponse.Raw> {

    public static final String SOURCE_ID = "semanticscholar";
    private static final String DEFAULT_BASE_URL = "https://api.semanticscholar.org/graph/v1/paper/search/bulk";
    private static final String FIELDS = "title,abstract,year,publicationDate,authors,venue,citationCount,"
            + "externalIds,url,publicationTypes,fieldsOfStudy";
    private static final int MIN_PHRASE_LENGTH = 3;

    private final ConnectorHttpClient http;
    private final ObjectMapper objectMapper;
    private final SemanticScholarNormalizer normalizer = new SemanticScholarNormalizer();
    private final String baseUrl;
    private final String apiKey;
    private final int requestsPerMinute;
    private final boolean enabled;

    public SemanticScholarConnector(
            ConnectorsProperties properties, ConnectorHttpClient http, ObjectMapper objectMapper) {
        var settings = properties.settings(SOURCE_ID);
        this.http = http;
        this.objectMapper = objectMapper;
        this.baseUrl = settings.baseUrlOr(DEFAULT_BASE_URL);
        this.apiKey = settings.apiKey() == null ? "" : settings.apiKey().trim();
        this.requestsPerMinute = settings.requestsPerMinuteOr(apiKey.isEmpty() ? 20 : 60);
        this.enabled = settings.enabledOr(true);
    }

    @Override
    public SourceDescriptor descriptor() {
        var descriptor = new SourceDescriptor(
                SOURCE_ID,
                "Semantic Scholar",
                SourceClass.JOURNAL_ARTICLE,
                Set.of(SourceClass.JOURNAL_ARTICLE, SourceClass.PREPRINT),
                requestsPerMinute,
                false,
                !apiKey.isEmpty(),
                null,
                false);
        return enabled ? descriptor : descriptor.switchedOff("Коннектор semanticscholar выключен конфигурацией");
    }

    @Override
    public boolean supports(CollectionRequest request) {
        return enabled && !request.isWildcard() && request.acceptsAnyOf(descriptor().providedClasses());
    }

    @Override
    protected DocumentNormalizer<SemanticScholarResponse.Raw> normalizer() {
        return normalizer;
    }

    @Override
    protected SourcePage<SemanticScholarResponse.Raw> fetchPage(CollectionRequest request, Cursor cursor) {
        var builder = UriComponentsBuilder.fromUriString(baseUrl)
                .queryParam("query", query(request.upstreamTerms()))
                .queryParam("publicationDateOrYear", "%s:%s".formatted(request.windowFrom(), request.windowTo()))
                .queryParam("fields", FIELDS);
        if (cursor != null && cursor.value() != null && !cursor.value().isBlank()) {
            builder.queryParam("token", cursor.value());
        }
        URI uri = builder.build().encode().toUri();
        Map<String, String> headers = apiKey.isEmpty()
                ? Map.of(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                : Map.of(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE, "x-api-key", apiKey);

        RawHttpResponse response = http.get(SOURCE_ID, uri, headers, requestsPerMinute);
        SemanticScholarResponse body =
                JsonBodies.parse(objectMapper, SOURCE_ID, response.body(), SemanticScholarResponse.class);

        List<SemanticScholarResponse.Raw> items = new ArrayList<>(body.data().size());
        for (SemanticScholarResponse.Paper paper : body.data()) {
            if (paper.paperId() == null || paper.paperId().isBlank()) {
                continue;
            }
            items.add(new SemanticScholarResponse.Raw(paper, SOURCE_ID, paper.paperId(), response.provenance()));
        }
        String next = body.token();
        boolean last = next == null || next.isBlank() || items.isEmpty();
        return new SourcePage<>(items, Cursor.ofValue(last ? null : next), last);
    }

    /** Цели направления через {@code |} — синтаксис ИЛИ массового поиска. */
    static String query(List<String> upstreamTerms) {
        List<String> phrases = SearchPhrases.of(upstreamTerms, MIN_PHRASE_LENGTH);
        return phrases.isEmpty() ? String.join(" ", upstreamTerms).trim() : SearchPhrases.joined(phrases, " | ");
    }
}
