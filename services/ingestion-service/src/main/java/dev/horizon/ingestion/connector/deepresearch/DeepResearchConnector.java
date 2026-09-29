package dev.horizon.ingestion.connector.deepresearch;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
import dev.horizon.ingestion.domain.document.Provenance;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.port.CollectionRequest;
import dev.horizon.ingestion.domain.port.DocumentNormalizer;
import dev.horizon.ingestion.domain.port.RawPayloadStore;
import dev.horizon.ingestion.domain.port.SourceDescriptor;
import dev.horizon.ingestion.domain.run.Cursor;
import dev.horizon.ingestion.domain.support.Hashing;

/**
 * Глубокое исследование: агент на модели Luna ищет по открытым каталогам проекта, читает страницы
 * целиком и называет технологии ранней стадии (разбор 102).
 *
 * <p><b>Что приходит в корпус.</b> Не имена и не отчёт модели, а <b>прочитанные страницы</b>: каждая
 * подтверждает хотя бы одно названное агентом имя, и это проверено сервисом моделей по полученному
 * тексту. Текст документа — дословные отрывки страницы, дата — из метаданных каталога или страницы,
 * класс — по тому, где страница найдена: arXiv — препринт, Crossref и Europe PMC — статья, GitHub —
 * репозиторий, остальное — новость. Дальше документ идёт тем же конвейером, что документы любого
 * другого источника: уровень доверенности по площадке, BRULE-1, скоринг. Так граница ТЗ §3.1
 * проходит по построению: знание модели может только подсказать, что искать и читать.
 *
 * <p><b>След.</b> Ответ сервиса моделей — вместе со следом: какие поиски заданы, какие страницы
 * прочитаны и зачем, какие отвергнуты {@code robots.txt}, какие имена не подтвердились — архивируется
 * целиком, и ссылка на архив стоит в происхождении каждого документа. Аудитор видит, почему страница
 * попала в корпус.
 *
 * <p><b>Один запрос на сбор.</b> Узкие формулировки расширения сюда не приходят: агент сам решает,
 * что спросить, и шесть минут на каждую формулировку не поместились бы в сагу.
 *
 * <p><b>Бюджет — по режиму анализа.</b> Быстрый анализ отдаёт агенту шесть минут, качественный —
 * двадцать и втрое больше страниц; режим приходит в запросе сбора, а вместе с ним меняется и то,
 * сколько ждать ответа.
 *
 * <p><b>Ноль и отказ различаются.</b> {@code status=failed} (модель не ответила, ни один поиск не
 * получил ответа, ни одна страница не прочиталась) — исключение, и источник попадает в перечень
 * недоступных. Прогон, где всё отвечало, но подтверждённых имён нет, — честный ноль.
 */
public class DeepResearchConnector extends AbstractSourceConnector<ResearchPage> {

    public static final String SOURCE_ID = "deepresearch";
    private static final Logger log = LoggerFactory.getLogger(DeepResearchConnector.class);
    private static final String DONE = "done";
    static final Set<SourceClass> PROVIDED = EnumSet.of(
            SourceClass.PREPRINT, SourceClass.JOURNAL_ARTICLE, SourceClass.CODE_REPOSITORY, SourceClass.NEWS);

    private final DeepResearchProperties research;
    private final DeepResearchClient client;
    private final ObjectMapper objectMapper;
    private final RawPayloadStore rawStore;
    private final Clock clock;
    private final boolean enabled;
    private final DeepResearchNormalizer normalizer = new DeepResearchNormalizer();

    public DeepResearchConnector(
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
                SOURCE_ID, "Глубокое исследование", SourceClass.NEWS, PROVIDED, 1, false, true, null, false);
        if (!enabled) {
            return descriptor.switchedOff("Глубокое исследование выключено (HORIZON_SOURCE_DEEPRESEARCH_ENABLED)");
        }
        if (research.nlpUrl().isBlank()) {
            return descriptor.unavailable("Не задан адрес сервиса моделей (HORIZON_NLP_URL)");
        }
        return descriptor;
    }

    @Override
    public boolean supports(CollectionRequest request) {
        return descriptor().available() && !request.isWildcard() && request.acceptsAnyOf(PROVIDED);
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
        DeepResearchProperties.Budget budget = research.budget(request.mode());
        String body = client.research(requestJson(request, budget), budget.requestTimeout());
        Instant received = clock.instant();
        JsonNode root;
        try {
            root = objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new ConnectorException.Permanent(SOURCE_ID, 200, "Ответ глубокого исследования не разбирается");
        }
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        String rawRef = rawStore.archive(SOURCE_ID, Hashing.sha256Hex(payload), received, payload, "application/json")
                .orElse(null);
        JsonNode stats = root.path("stats");
        logStats(request, root, stats, rawRef);
        if (!"ok".equals(root.path("status").asText())) {
            throw new ConnectorException.Permanent(
                    SOURCE_ID,
                    0,
                    "Глубокое исследование не получило ответа источников: поисков %d, отказов %d, прочитано %d, отказов чтения %d%s"
                            .formatted(
                                    stats.path("searches").asInt(),
                                    stats.path("searchesFailed").asInt(),
                                    stats.path("pagesFetched").asInt(),
                                    stats.path("pagesFailed").asInt(),
                                    stats.hasNonNull("modelError") ? "; модель: " + stats.path("modelError").asText() : ""));
        }
        List<ResearchPage> pages = new ArrayList<>();
        for (JsonNode node : root.path("documents")) {
            ResearchPage page = pageOf(SOURCE_ID, node, rawRef, received);
            if (page == null) {
                continue;
            }
            SourceClass sourceClass = SourceClass.valueOf(page.sourceClass());
            if (request.accepts(sourceClass) && request.withinWindow(page.publishedOn())) {
                pages.add(page);
            }
        }
        return SourcePage.last(pages, Cursor.ofValue(DONE));
    }

    /**
     * Тело запроса к сервису моделей. Бюджет — по режиму анализа: сервис моделей держит у себя
     * только потолки, а сколько времени и страниц отдать этому прогону, решает запрос.
     */
    private String requestJson(CollectionRequest request, DeepResearchProperties.Budget budget) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", request.query());
        body.put("targets", request.subjectTargets());
        body.put("windowFrom", request.windowFrom().toString());
        body.put("windowTo", request.windowTo().toString());
        body.put("maxIterations", budget.maxIterations());
        body.put("maxFetches", Math.min(budget.maxFetches(), request.maxDocuments()));
        // Агенту — бюджет без полуминуты: ей закрывается последний ход и собирается ответ.
        body.put("timeBudgetSeconds", budget.agentSeconds());
        body.put("minSources", Math.min(budget.minSources(), budget.maxFetches()));
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private void logStats(CollectionRequest request, JsonNode root, JsonNode stats, String rawRef) {
        // Раскрытие модели (ТЗ §3.1) и доля отказов чтения — в журнал каждого прогона.
        log.info(
                "Глубокое исследование «{}» ({}) моделью {}: статус {}, ходов {}, поисков {} (пустых {}, отказов {}),"
                        + " страниц прочитано {}, запрещено robots.txt {}, отказов {} (доля {}), документов {},"
                        + " имён записано {}, с документами {}, токенов {}+{}, {} с, остановка {}; след {}",
                request.query(),
                request.mode(),
                root.path("model").asText(),
                root.path("status").asText(),
                stats.path("iterations").asInt(),
                stats.path("searches").asInt(),
                stats.path("searchesEmpty").asInt(),
                stats.path("searchesFailed").asInt(),
                stats.path("pagesFetched").asInt(),
                stats.path("pagesRefused").asInt(),
                stats.path("pagesFailed").asInt(),
                stats.path("failedFetchShare").asDouble(),
                stats.path("documentsEmitted").asInt(),
                stats.path("namesRecorded").asInt(),
                stats.path("namesWithDocuments").asInt(),
                stats.path("promptTokens").asInt(),
                stats.path("completionTokens").asInt(),
                stats.path("wallSeconds").asDouble(),
                stats.path("stopReason").asText(),
                rawRef);
    }

    static ResearchPage pageOf(String sourceId, JsonNode node, String rawRef, Instant received) {
        String url = text(node, "url");
        String title = text(node, "title");
        String sha = text(node, "sha256");
        LocalDate published;
        try {
            published = LocalDate.parse(text(node, "publishedOn"));
        } catch (RuntimeException e) {
            return null;
        }
        String sourceClass = text(node, "sourceClass").toUpperCase(Locale.ROOT);
        if (url.isEmpty() || title.isEmpty() || sha.length() != 64 || !isKnownClass(sourceClass)) {
            return null;
        }
        Instant fetchedAt = received;
        try {
            fetchedAt = OffsetDateTime.parse(text(node, "fetchedAt")).toInstant();
        } catch (DateTimeParseException e) {
            // Время чтения — не свидетельство; без него берётся время ответа сервиса.
        }
        int status = node.path("httpStatus").asInt(200);
        Provenance provenance = new Provenance(sourceId, fetchedAt, url, status, sha, rawRef);
        return new ResearchPage(
                sourceId,
                Hashing.sha256Hex(url).substring(0, 40),
                provenance,
                url,
                title,
                published,
                sourceClass,
                text(node, "origin"),
                text(node, "host"),
                text(node, "language"),
                text(node, "excerpt"),
                strings(node.path("authors")),
                emptyToNull(text(node, "organization")),
                node.path("organizationIsCompany").asBoolean(false),
                emptyToNull(text(node, "doi")),
                emptyToNull(text(node, "arxivId")),
                emptyToNull(text(node, "venue")),
                text(node, "readReason"),
                strings(node.path("technologies")));
    }

    private static boolean isKnownClass(String value) {
        try {
            return PROVIDED.contains(SourceClass.valueOf(value));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("").trim();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        for (JsonNode item : array) {
            String value = item.asText("").trim();
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values;
    }
}
