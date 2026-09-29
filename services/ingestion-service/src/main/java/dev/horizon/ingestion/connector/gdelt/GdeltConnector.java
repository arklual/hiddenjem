package dev.horizon.ingestion.connector.gdelt;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizon.ingestion.config.ConnectorsProperties;
import dev.horizon.ingestion.connector.gdelt.model.GdeltResponse;
import dev.horizon.ingestion.connector.support.AbstractSourceConnector;
import dev.horizon.ingestion.connector.support.ConnectorException;
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
import dev.horizon.ingestion.domain.support.Hashing;

/**
 * GDELT DOC 2.0 — мировые новости, в том числе русскоязычные.
 *
 * <p>Исследование к кейсу кладёт GDELT в минимальный набор источников как меру медийной видимости:
 * высокая видимость при слабой научной опоре — признак хайпа. Второй довод — ТЗ: выдача строится по
 * «открытым русскоязычным и зарубежным источникам», а GDELT переводит новости шестидесяти пяти
 * языков и находит русскоязычную заметку по английской формулировке. Поэтому запросов два: все
 * языки и отдельно русские источники.
 *
 * <p><b>Два запроса, и не больше.</b> GDELT просит не чаще одного запроса в пять секунд и на деле
 * отвечает {@code 429} и при восьми (замер валидации: 135 отказов из 173). Окно новостей —
 * тридцать шесть месяцев: дальше API не ищет.
 */
public class GdeltConnector extends AbstractSourceConnector<GdeltResponse.Raw> {

    public static final String SOURCE_ID = "gdelt";
    private static final String DEFAULT_BASE_URL = "https://api.gdeltproject.org/api/v2/doc/doc";
    /** GDELT отвергает запрос со словом короче трёх букв: «ai» роняет весь запрос. */
    private static final int MIN_PHRASE_LENGTH = 4;
    /** Больше шести фраз через ИЛИ GDELT принимает неохотно, а седьмая почти ничего не добавляет. */
    private static final int MAX_PHRASES = 6;
    private static final List<String> LANGUAGE_FILTERS = List.of("", " sourcelang:russian");

    private final ConnectorHttpClient http;
    private final ObjectMapper objectMapper;
    private final GdeltNormalizer normalizer = new GdeltNormalizer();
    private final String baseUrl;
    private final int requestsPerMinute;
    private final boolean enabled;

    public GdeltConnector(ConnectorsProperties properties, ConnectorHttpClient http, ObjectMapper objectMapper) {
        var settings = properties.settings(SOURCE_ID);
        this.http = http;
        this.objectMapper = objectMapper;
        this.baseUrl = settings.baseUrlOr(DEFAULT_BASE_URL);
        // Три в минуту — двадцать секунд между запросами: на пяти GDELT уже отказывает.
        this.requestsPerMinute = settings.requestsPerMinuteOr(3);
        this.enabled = settings.enabledOr(true);
    }

    @Override
    public SourceDescriptor descriptor() {
        var descriptor = new SourceDescriptor(
                SOURCE_ID,
                "GDELT (мировые новости)",
                SourceClass.NEWS,
                Set.of(SourceClass.NEWS),
                requestsPerMinute,
                false,
                true,
                null,
                false);
        return enabled ? descriptor : descriptor.switchedOff("Коннектор gdelt выключен конфигурацией");
    }

    @Override
    public boolean supports(CollectionRequest request) {
        return enabled && !request.isWildcard() && request.accepts(SourceClass.NEWS);
    }

    @Override
    protected DocumentNormalizer<GdeltResponse.Raw> normalizer() {
        return normalizer;
    }

    @Override
    protected SourcePage<GdeltResponse.Raw> fetchPage(CollectionRequest request, Cursor cursor) {
        int index = indexOf(cursor);
        String expression = query(request.upstreamTerms());
        if (expression.isEmpty()) {
            return new SourcePage<>(List.of(), Cursor.ofValue("0"), true);
        }
        URI uri = UriComponentsBuilder.fromUriString(baseUrl)
                .queryParam("query", expression + LANGUAGE_FILTERS.get(index))
                .queryParam("mode", "artlist")
                .queryParam("format", "json")
                .queryParam("maxrecords", 250)
                .queryParam("timespan", "36months")
                .queryParam("sort", "hybridrel")
                .build()
                .encode()
                .toUri();
        RawHttpResponse response = http.get(
                SOURCE_ID, uri, Map.of(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE), requestsPerMinute);
        String body = response.body() == null ? "" : response.body().trim();
        GdeltResponse parsed;
        if (body.isEmpty() || body.equals("{}")) {
            parsed = new GdeltResponse(List.of());
        } else if (!body.startsWith("{")) {
            // GDELT объясняет отказ человекочитаемым текстом, иногда с кодом 200. Это не «ноль
            // статей», а отказ источника — и отчёт обязан это знать.
            throw new ConnectorException.Retryable(SOURCE_ID, 200, "GDELT отказал: "
                    + body.substring(0, Math.min(body.length(), 160)));
        } else {
            parsed = JsonBodies.parse(objectMapper, SOURCE_ID, body, GdeltResponse.class);
        }

        List<GdeltResponse.Raw> items = new ArrayList<>(parsed.articles().size());
        for (GdeltResponse.Article article : parsed.articles()) {
            if (article.url() == null || article.url().isBlank()) {
                continue;
            }
            String url = article.url().trim();
            String externalId = url.length() <= 200
                    ? url
                    : Hashing.sha256Hex(url.getBytes(StandardCharsets.UTF_8));
            items.add(new GdeltResponse.Raw(article, SOURCE_ID, externalId, response.provenance()));
        }
        boolean last = index + 1 >= LANGUAGE_FILTERS.size();
        return new SourcePage<>(items, Cursor.ofValue(Integer.toString(index + 1)), last);
    }

    /** {@code ("a" OR "b")}: GDELT требует скобки вокруг ИЛИ. */
    static String query(List<String> upstreamTerms) {
        List<String> phrases = SearchPhrases.of(upstreamTerms, MIN_PHRASE_LENGTH);
        if (phrases.isEmpty()) {
            return "";
        }
        List<String> head = phrases.size() > MAX_PHRASES ? phrases.subList(0, MAX_PHRASES) : phrases;
        String joined = String.join(" OR ", head.stream().map(phrase -> "\"" + phrase + "\"").toList());
        return head.size() == 1 ? joined : "(" + joined + ")";
    }

    private static int indexOf(Cursor cursor) {
        if (cursor == null || cursor.value() == null) {
            return 0;
        }
        try {
            return Math.min(Math.max(0, Integer.parseInt(cursor.value())), LANGUAGE_FILTERS.size() - 1);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
