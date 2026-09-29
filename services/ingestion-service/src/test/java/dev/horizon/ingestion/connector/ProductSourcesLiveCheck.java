package dev.horizon.ingestion.connector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizon.ingestion.config.ConnectorConfiguration;
import dev.horizon.ingestion.config.ConnectorsProperties;
import dev.horizon.ingestion.connector.habr.HabrConnector;
import dev.horizon.ingestion.connector.industry.IndustryMediaConnector;
import dev.horizon.ingestion.connector.support.AbstractSourceConnector;
import dev.horizon.ingestion.connector.support.ConnectorHttpClient;
import dev.horizon.ingestion.connector.support.InMemoryRateLimiters;
import dev.horizon.ingestion.connector.support.NoopRawPayloadStore;
import dev.horizon.ingestion.domain.document.Author;
import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.port.CollectionRequest;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Живая проверка продуктовых источников: настоящая сеть, настоящие ответы, ни одной подмены.
 *
 * <p>В обычном прогоне пропускается: тест ходит в чужие сайты и длится минуты. Запуск —
 * {@code ./mvnw -pl ingestion-service test -Dtest=ProductSourcesLiveCheck -Dhorizon.live=true
 * -Dsurefire.failIfNoSpecifiedTests=false}. Печатает по каждому направлению датасета и источнику:
 * сколько документов, за сколько секунд, какой диапазон дат, у скольких формулировок ноль, и отказал
 * ли источник — ноль и отказ обязаны различаться (разбор 101). С {@code -Dhorizon.live.out=<каталог>}
 * пишет собранные документы в JSONL формата {@code DocumentIngested} — вход воронки отсева
 * аналитического сервиса ({@code horizon_analytics.validation.funnel}).
 */
class ProductSourcesLiveCheck {

    /** Запросы направлений так, как их отправляет сервис сбора: формулировка аналитика и цели словаря. */
    private static final Map<String, List<String>> DIRECTIONS = new LinkedHashMap<>();

    static {
        DIRECTIONS.put(
                "edge",
                List.of(
                        "периферийные вычисления",
                        "edge computing",
                        "mobile edge computing",
                        "distributed computing",
                        "cs.DC"));
        DIRECTIONS.put(
                "ai-security",
                List.of(
                        "защита искусственного интеллекта",
                        "computer security",
                        "adversarial machine learning",
                        "cs.CR",
                        "cs.LG",
                        "ai safety"));
        DIRECTIONS.put(
                "industrial-ai",
                List.of(
                        "индустриальный искусственный интеллект",
                        "artificial intelligence",
                        "manufacturing",
                        "industrial engineering",
                        "cs.LG",
                        "automation"));
        DIRECTIONS.put(
                "ai-infrastructure",
                List.of(
                        "инфраструктура искусственного интеллекта",
                        "distributed computing",
                        "cs.DC",
                        "computer architecture",
                        "cs.AR",
                        "cloud computing"));
        DIRECTIONS.put(
                "robotics",
                List.of("робототехника", "robotics", "cs.RO", "autonomous robot", "robot control", "mobile robot"));
        DIRECTIONS.put("fintech", List.of("финтех", "fintech", "finance", "payments", "mobile payment"));
    }

    @Test
    @DisplayName("Хабр и отраслевые медиа отвечают на запросы шести направлений")
    void collectLive() throws IOException {
        assumeTrue(Boolean.getBoolean("horizon.live"), "живая проверка включается -Dhorizon.live=true");
        String only = System.getProperty("horizon.live.directions", "");
        String sources = System.getProperty("horizon.live.sources", "habr,industry");
        String out = System.getProperty("horizon.live.out", "");
        String email = System.getenv().getOrDefault("HORIZON_CONNECTOR_CONTACT_EMAIL", "");
        var industryFeeds = List.of(
                "https://www.edgeir.com/search/{q}/feed/rss2/?paged={page}",
                "https://siliconangle.com/search/{q}/feed/rss2/?paged={page}",
                "https://www.plantengineering.com/search/{q}/feed/rss2/?paged={page}",
                "https://robohub.org/search/{q}/feed/rss2/?paged={page}",
                "https://cyberscoop.com/search/{q}/feed/rss2/?paged={page}",
                "https://thefintechtimes.com/search/{q}/feed/rss2/?paged={page}");
        var properties = new ConnectorsProperties(
                "HorizonBot/1.0",
                email,
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                0,
                null,
                Map.of(
                        HabrConnector.SOURCE_ID,
                        new ConnectorsProperties.ConnectorSettings(
                                true, null, 6, null, null, null, null, List.of(), null),
                        IndustryMediaConnector.SOURCE_ID,
                        new ConnectorsProperties.ConnectorSettings(
                                true, null, 6, null, null, null, null, industryFeeds, null)));
        var http = new ConnectorHttpClient(
                new ConnectorConfiguration().connectorWebClient(properties),
                new InMemoryRateLimiters(1),
                new NoopRawPayloadStore(),
                properties,
                Clock.systemUTC(),
                new SimpleMeterRegistry());
        Map<String, AbstractSourceConnector<?>> connectors = new LinkedHashMap<>();
        if (sources.contains("habr")) {
            connectors.put(HabrConnector.SOURCE_ID, new HabrConnector(properties, http));
        }
        if (sources.contains("industry")) {
            connectors.put(IndustryMediaConnector.SOURCE_ID, new IndustryMediaConnector(properties, http));
        }

        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        LocalDate today = LocalDate.now();
        int answered = 0;
        for (var direction : DIRECTIONS.entrySet()) {
            if (!only.isBlank() && !only.contains(direction.getKey())) {
                continue;
            }
            String query = direction.getValue().get(0);
            List<String> targets =
                    direction.getValue().subList(1, direction.getValue().size());
            var request = new CollectionRequest(
                    UUID.randomUUID(),
                    query,
                    query,
                    "ru",
                    LocalDate.of(today.getYear() - 6, 1, 1),
                    today,
                    Set.of(),
                    5000,
                    targets);
            for (var entry : connectors.entrySet()) {
                long started = System.nanoTime();
                List<Document> documents = new ArrayList<>();
                String failure = null;
                try (var stream = entry.getValue().collect(request, null)) {
                    stream.documents().forEach(documents::add);
                } catch (RuntimeException e) {
                    failure = e.getClass().getSimpleName() + ": " + e.getMessage();
                }
                double seconds = (System.nanoTime() - started) / 1e9;
                LocalDate oldest = documents.stream()
                        .map(Document::publishedOn)
                        .min(LocalDate::compareTo)
                        .orElse(null);
                LocalDate newest = documents.stream()
                        .map(Document::publishedOn)
                        .max(LocalDate::compareTo)
                        .orElse(null);
                long organizations = documents.stream()
                        .flatMap(document -> document.authors().stream())
                        .map(Author::organizationName)
                        .filter(name -> name != null && !name.isBlank())
                        .distinct()
                        .count();
                long distinct = documents.stream()
                        .map(d -> d.externalRef().externalId())
                        .distinct()
                        .count();
                long thisYear = documents.stream()
                        .filter(d -> d.publishedOn().getYear() == today.getYear())
                        .count();
                System.out.printf(
                        "LIVE %-18s %-9s %s документов %4d (различных %4d)  за %6.1f с  даты %s..%s  текущего года %d  организаций %d%s%n",
                        direction.getKey(),
                        entry.getKey(),
                        failure == null ? "ok   " : "ОТКАЗ",
                        documents.size(),
                        distinct,
                        seconds,
                        oldest,
                        newest,
                        thisYear,
                        organizations,
                        failure == null ? "" : "  " + failure);
                if (failure == null) {
                    answered++;
                }
                if (!out.isBlank()) {
                    write(json, Path.of(out, direction.getKey() + "-" + entry.getKey() + ".jsonl"), documents);
                }
            }
        }
        assertThat(answered).as("хоть один источник ответил").isPositive();
    }

    /** Документ в форме {@code DocumentIngested} — той, что читает аналитический сервис. */
    private static void write(ObjectMapper json, Path path, List<Document> documents) throws IOException {
        Files.createDirectories(path.getParent());
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            for (Document document : documents) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("documentId", document.id().toString());
                payload.put("sourceId", document.externalRef().sourceId());
                payload.put("sourceClass", document.sourceClass().name());
                payload.put("externalId", document.externalRef().externalId());
                payload.put("title", document.title());
                payload.put("abstractText", document.abstractText());
                payload.put("language", document.language());
                payload.put("publishedOn", document.publishedOn().toString());
                payload.put("url", document.identifiers().url());
                payload.put(
                        "venue",
                        document.venue() == null
                                ? null
                                : Map.of(
                                        "name",
                                        document.venue().name(),
                                        "type",
                                        String.valueOf(document.venue().type())));
                List<Map<String, Object>> authors = new ArrayList<>();
                for (Author author : document.authors()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("fullName", author.fullName());
                    row.put("organizationName", author.organizationName());
                    row.put(
                            "organizationType",
                            author.organizationType() == null
                                    ? null
                                    : author.organizationType().name());
                    authors.add(row);
                }
                payload.put("authors", authors);
                payload.put("topics", List.of());
                payload.put("fetchedAt", document.provenance().fetchedAt().toString());
                payload.put(
                        "dedupKey",
                        document.dedupKey() == null ? "" : document.dedupKey().value());
                writer.write(json.writeValueAsString(payload));
                writer.write('\n');
            }
        }
    }
}
