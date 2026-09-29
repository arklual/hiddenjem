package dev.horizon.ingestion.connector.deepresearch;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizon.ingestion.config.ConnectorsProperties;
import dev.horizon.ingestion.config.DeepResearchProperties;
import dev.horizon.ingestion.connector.deepresearch.model.ResearchPage;
import dev.horizon.ingestion.connector.support.AbstractSourceConnector;
import dev.horizon.ingestion.connector.support.ConnectorException;
import dev.horizon.ingestion.connector.support.SourcePage;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.port.CollectionRequest;
import dev.horizon.ingestion.domain.port.DocumentNormalizer;
import dev.horizon.ingestion.domain.port.RawPayloadStore;
import dev.horizon.ingestion.domain.port.SourceDescriptor;
import dev.horizon.ingestion.domain.run.Cursor;
import dev.horizon.ingestion.domain.support.Hashing;

/**
 * Собственный веб-корпус (разбор 110): страницы компаний, пресс-релизы, ленты новостей и отраслевые
 * издания, собранные заранее сборщиком по корзине вероятных запросов.
 *
 * <p>Сервис моделей держит корпус в памяти и отвечает на {@code POST /webcorpus/search} в той же
 * форме, что глубокое исследование, поэтому разбор страницы и нормализатор — общие. Модели в этом
 * пути нет: поиск BM25 и совпадение с направлением корзины, ответ за секунды. Каждая страница
 * корпуса скачана с проверкой {@code robots.txt}, включая поимённые запреты ИИ-краулерам, —
 * отметка проверки стоит в причине чтения.
 *
 * <p>В отличие от глубокого исследования, источник получает узкие формулировки расширения: поиск
 * дешёвый, и каждая формулировка находит свои страницы.
 */
public class WebCorpusConnector extends AbstractSourceConnector<ResearchPage> {

    public static final String SOURCE_ID = "webcorpus";
    private static final Logger log = LoggerFactory.getLogger(WebCorpusConnector.class);
    private static final String DONE = "done";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private final DeepResearchProperties research;
    private final DeepResearchClient client;
    private final ObjectMapper objectMapper;
    private final RawPayloadStore rawStore;
    private final Clock clock;
    private final boolean enabled;
    private final DeepResearchNormalizer normalizer = new DeepResearchNormalizer();

    public WebCorpusConnector(
            ConnectorsProperties properties,
            DeepResearchProperties research,
            DeepResearchClient client,
            ObjectMapper objectMapper,
            RawPayloadStore rawStore,
            Clock clock) {
        this.research = research;
        this.client = client;
        this.objectMapper = objectMapper;
        this.rawStore = rawStore;
        this.clock = clock;
        this.enabled = properties.settings(SOURCE_ID).enabledOr(false);
    }

    @Override
    public SourceDescriptor descriptor() {
        var descriptor = new SourceDescriptor(
                SOURCE_ID, "Веб-корпус", SourceClass.NEWS, EnumSet.of(SourceClass.NEWS), 30, false, true,
                null, false);
        if (!enabled) {
            return descriptor.switchedOff("Веб-корпус выключен (HORIZON_SOURCE_WEBCORPUS_ENABLED)");
        }
        if (research.nlpUrl().isBlank()) {
            return descriptor.unavailable("Не задан адрес сервиса моделей (HORIZON_NLP_URL)");
        }
        return descriptor;
    }

    @Override
    public boolean supports(CollectionRequest request) {
        return descriptor().available() && !request.isWildcard() && request.accepts(SourceClass.NEWS);
    }

    @Override
    protected DocumentNormalizer<ResearchPage> normalizer() {
        return normalizer;
    }

    @Override
    protected SourcePage<ResearchPage> fetchPage(CollectionRequest request, Cursor cursor) {
        if (cursor != null && DONE.equals(cursor.value())) {
            return SourcePage.empty();
        }
        String body = client.research(requestJson(request), TIMEOUT);
        Instant received = clock.instant();
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new ConnectorException.Permanent(SOURCE_ID, 200, "Ответ веб-корпуса не разбирается");
        }
        if (!"ok".equals(root.path("status").asText())) {
            throw new ConnectorException.Permanent(
                    SOURCE_ID, 0, "Веб-корпус недоступен: " + root.path("stats").path("reason").asText("нет причины"));
        }
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        String rawRef = rawStore.archive(SOURCE_ID, Hashing.sha256Hex(payload), received, payload, "application/json")
                .orElse(null);
        List<ResearchPage> pages = new ArrayList<>();
        for (JsonNode node : root.path("documents")) {
            ResearchPage page = DeepResearchConnector.pageOf(SOURCE_ID, node, rawRef, received);
            if (page != null && request.withinWindow(page.publishedOn())) {
                pages.add(page);
            }
        }
        log.info("Веб-корпус «{}»: страниц {} из корпуса {}", request.query(), pages.size(),
                root.path("stats").path("corpusSize").asInt());
        return SourcePage.last(pages, Cursor.ofValue(DONE));
    }

    private String requestJson(CollectionRequest request) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", request.query());
        body.put("targets", request.subjectTargets());
        body.put("windowFrom", request.windowFrom().toString());
        body.put("windowTo", request.windowTo().toString());
        body.put("limit", Math.max(1, Math.min(300, request.maxDocuments())));
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
