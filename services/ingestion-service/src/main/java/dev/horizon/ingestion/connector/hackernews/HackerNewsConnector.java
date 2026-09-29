package dev.horizon.ingestion.connector.hackernews;

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
import dev.horizon.ingestion.connector.hackernews.model.HackerNewsResponse;
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
 * Hacker News — раннее внимание разработчиков.
 *
 * <p>Исследование к кейсу выделяет «раннее внимание» отдельным слоем свидетельств: новые названия,
 * первые демонстрации и поводы для дальнейшего поиска появляются в обсуждениях раньше, чем в
 * журналах. Цена та же, что у соцсетей: пересказ не является независимым подтверждением. Поэтому
 * документ Hacker News — это страница обсуждения, а её хост правила доверенности уже знают как
 * агрегатор: пониженная доверенность, не независимое свидетельство. По ТЗ такие источники —
 * «первичный индикатор», и ровно так они и участвуют.
 *
 * <p><b>Запрос на каждую цель.</b> Поиск Algolia не знает ИЛИ, поэтому цели направления
 * спрашиваются по очереди; курсор — «номер цели:страница».
 */
public class HackerNewsConnector extends AbstractSourceConnector<HackerNewsResponse.Raw> {

    public static final String SOURCE_ID = "hackernews";
    private static final String DEFAULT_BASE_URL = "https://hn.algolia.com/api/v1/search";
    private static final int MIN_PHRASE_LENGTH = 3;
    /** Больше пяти страниц на цель не спрашиваем: дальше релевантность поиска Algolia падает до шума. */
    private static final int MAX_PAGES_PER_TERM = 5;

    private final ConnectorHttpClient http;
    private final ObjectMapper objectMapper;
    private final HackerNewsNormalizer normalizer = new HackerNewsNormalizer();
    private final String baseUrl;
    private final int pageSize;
    private final int requestsPerMinute;
    private final boolean enabled;

    public HackerNewsConnector(ConnectorsProperties properties, ConnectorHttpClient http, ObjectMapper objectMapper) {
        var settings = properties.settings(SOURCE_ID);
        this.http = http;
        this.objectMapper = objectMapper;
        this.baseUrl = settings.baseUrlOr(DEFAULT_BASE_URL);
        this.pageSize = Math.min(settings.pageSizeOr(100), 1000);
        this.requestsPerMinute = settings.requestsPerMinuteOr(60);
        this.enabled = settings.enabledOr(true);
    }

    @Override
    public SourceDescriptor descriptor() {
        var descriptor = new SourceDescriptor(
                SOURCE_ID,
                "Hacker News",
                SourceClass.NEWS,
                Set.of(SourceClass.NEWS),
                requestsPerMinute,
                false,
                true,
                null,
                false);
        return enabled ? descriptor : descriptor.switchedOff("Коннектор hackernews выключен конфигурацией");
    }

    @Override
    public boolean supports(CollectionRequest request) {
        return enabled && !request.isWildcard() && request.accepts(SourceClass.NEWS);
    }

    @Override
    protected DocumentNormalizer<HackerNewsResponse.Raw> normalizer() {
        return normalizer;
    }

    @Override
    protected SourcePage<HackerNewsResponse.Raw> fetchPage(CollectionRequest request, Cursor cursor) {
        List<String> phrases = SearchPhrases.of(request.upstreamTerms(), MIN_PHRASE_LENGTH);
        if (phrases.isEmpty()) {
            return new SourcePage<>(List.of(), Cursor.ofValue("0:0"), true);
        }
        int[] position = positionOf(cursor);
        int term = Math.min(position[0], phrases.size() - 1);
        int page = position[1];

        long from = request.windowFrom().toEpochDay() * 86_400L;
        long to = (request.windowTo().toEpochDay() + 1) * 86_400L;
        URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                .queryParam("query", "\"" + phrases.get(term) + "\"")
                .queryParam("tags", "story")
                .queryParam("advancedSyntax", "true")
                .queryParam("numericFilters", "created_at_i>=%d,created_at_i<%d".formatted(from, to))
                .queryParam("hitsPerPage", pageSize)
                .queryParam("page", page)
                .build()
                .encode()
                .toUri();
        RawHttpResponse response = http.get(
                SOURCE_ID, uri, Map.of(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE), requestsPerMinute);
        HackerNewsResponse body = JsonBodies.parse(objectMapper, SOURCE_ID, response.body(), HackerNewsResponse.class);

        List<HackerNewsResponse.Raw> items = new ArrayList<>(body.hits().size());
        for (HackerNewsResponse.Hit hit : body.hits()) {
            if (hit.objectId() != null && !hit.objectId().isBlank()) {
                items.add(new HackerNewsResponse.Raw(hit, SOURCE_ID, hit.objectId(), response.provenance()));
            }
        }
        int pages = body.nbPages() == null ? 0 : body.nbPages();
        boolean termDone = items.isEmpty() || page + 1 >= Math.min(pages, MAX_PAGES_PER_TERM);
        boolean last = termDone && term + 1 >= phrases.size();
        String next = termDone ? (term + 1) + ":0" : term + ":" + (page + 1);
        return new SourcePage<>(items, Cursor.ofValue(next), last);
    }

    private static int[] positionOf(Cursor cursor) {
        if (cursor == null || cursor.value() == null || !cursor.value().contains(":")) {
            return new int[] {0, 0};
        }
        String[] parts = cursor.value().split(":", 2);
        try {
            return new int[] {Math.max(0, Integer.parseInt(parts[0])), Math.max(0, Integer.parseInt(parts[1]))};
        } catch (NumberFormatException e) {
            return new int[] {0, 0};
        }
    }
}
