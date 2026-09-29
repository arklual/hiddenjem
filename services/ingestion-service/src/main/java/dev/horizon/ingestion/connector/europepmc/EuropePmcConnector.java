package dev.horizon.ingestion.connector.europepmc;

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
import dev.horizon.ingestion.connector.europepmc.model.EuropePmcResponse;
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
 * Europe PMC — публикации и препринты наук о жизни, с аффилиациями авторов.
 *
 * <p>Нужен ради двух вещей, которых у остальных источников мало. Первая — аффилиации: у препринтов
 * arXiv организация указана в 3% случаев, а правило «две независимые организации» без них теряет
 * самые свежие темы (разбор 88). Europe PMC отдаёт аффилиацию почти каждого автора. Вторая —
 * биомедицина: направления вроде биотеха и медицинского ИИ опираются на литературу, которой в
 * arXiv почти нет.
 *
 * <p>Цели направления соединяются через {@code OR}, окно — фильтром {@code FIRST_PDATE}.
 * Продолжение — по {@code cursorMark}.
 */
public class EuropePmcConnector extends AbstractSourceConnector<EuropePmcResponse.Raw> {

    public static final String SOURCE_ID = "europepmc";
    private static final String DEFAULT_BASE_URL = "https://www.ebi.ac.uk/europepmc/webservices/rest/search";
    private static final String CURSOR_START = "*";
    private static final int MIN_PHRASE_LENGTH = 3;

    private final ConnectorHttpClient http;
    private final ObjectMapper objectMapper;
    private final EuropePmcNormalizer normalizer = new EuropePmcNormalizer();
    private final String baseUrl;
    private final int pageSize;
    private final int requestsPerMinute;
    private final boolean enabled;

    public EuropePmcConnector(ConnectorsProperties properties, ConnectorHttpClient http, ObjectMapper objectMapper) {
        var settings = properties.settings(SOURCE_ID);
        this.http = http;
        this.objectMapper = objectMapper;
        this.baseUrl = settings.baseUrlOr(DEFAULT_BASE_URL);
        // Ответ с аннотациями и аффилиациями тяжёлый: двести записей — около мегабайта.
        this.pageSize = Math.min(settings.pageSizeOr(200), 1000);
        this.requestsPerMinute = settings.requestsPerMinuteOr(60);
        this.enabled = settings.enabledOr(true);
    }

    @Override
    public SourceDescriptor descriptor() {
        var descriptor = new SourceDescriptor(
                SOURCE_ID,
                "Europe PMC",
                SourceClass.JOURNAL_ARTICLE,
                Set.of(SourceClass.JOURNAL_ARTICLE, SourceClass.PREPRINT),
                requestsPerMinute,
                false,
                true,
                null,
                false);
        return enabled ? descriptor : descriptor.switchedOff("Коннектор europepmc выключен конфигурацией");
    }

    @Override
    public boolean supports(CollectionRequest request) {
        return enabled && !request.isWildcard() && request.acceptsAnyOf(descriptor().providedClasses());
    }

    @Override
    protected DocumentNormalizer<EuropePmcResponse.Raw> normalizer() {
        return normalizer;
    }

    @Override
    protected SourcePage<EuropePmcResponse.Raw> fetchPage(CollectionRequest request, Cursor cursor) {
        String cursorValue = cursor == null || cursor.value() == null ? CURSOR_START : cursor.value();
        URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                .queryParam("query", query(request))
                .queryParam("format", "json")
                .queryParam("resultType", "core")
                .queryParam("pageSize", pageSize)
                .queryParam("cursorMark", cursorValue)
                .build()
                .encode()
                .toUri();
        RawHttpResponse response = http.get(
                SOURCE_ID, uri, Map.of(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE), requestsPerMinute);
        EuropePmcResponse body = JsonBodies.parse(objectMapper, SOURCE_ID, response.body(), EuropePmcResponse.class);

        List<EuropePmcResponse.Raw> items = new ArrayList<>(body.results().size());
        for (EuropePmcResponse.Result result : body.results()) {
            if (result.id() == null || result.id().isBlank() || result.source() == null) {
                continue;
            }
            String externalId = result.source() + ":" + result.id();
            items.add(new EuropePmcResponse.Raw(result, SOURCE_ID, externalId, response.provenance()));
        }
        String next = body.nextCursorMark();
        boolean last = items.isEmpty() || next == null || next.isBlank() || next.equals(cursorValue);
        return new SourcePage<>(items, Cursor.ofValue(last ? cursorValue : next), last);
    }

    /** {@code ("a" OR "b") AND FIRST_PDATE:[с TO по]}. */
    static String query(CollectionRequest request) {
        List<String> phrases = SearchPhrases.of(request.upstreamTerms(), MIN_PHRASE_LENGTH);
        String terms = phrases.isEmpty()
                ? request.upstreamQuery()
                : phrases.size() == 1 ? "\"" + phrases.get(0) + "\"" : "(" + SearchPhrases.joined(phrases, " OR ") + ")";
        return "%s AND FIRST_PDATE:[%s TO %s]".formatted(terms, request.windowFrom(), request.windowTo());
    }
}
