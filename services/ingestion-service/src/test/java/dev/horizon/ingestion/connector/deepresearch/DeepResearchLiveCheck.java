package dev.horizon.ingestion.connector.deepresearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizon.ingestion.config.ConnectorsProperties;
import dev.horizon.ingestion.config.DeepResearchProperties;
import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.port.CollectionRequest;
import dev.horizon.ingestion.domain.port.RawPayloadStore;

/**
 * Живая проверка глубокого исследования: настоящий сервис моделей, настоящая Luna, настоящие
 * каталоги и страницы — ни одной подмены.
 *
 * <p>В обычном прогоне пропускается: идёт минуты и стоит токенов. Запуск — поднять сервис моделей с
 * облачным бэкендом и {@code ./mvnw -pl ingestion-service test -Dtest=DeepResearchLiveCheck
 * -Dhorizon.live=true -Dhorizon.nlp.url=http://127.0.0.1:18086 -Dhorizon.live.out=<каталог>
 * -Dsurefire.failIfNoSpecifiedTests=false}. Ответ сервиса моделей (документы, след, счёт) пишется в
 * каталог как есть — по нему считаются числа разбора 102.
 */
class DeepResearchLiveCheck {

    private static final Map<String, List<String>> DIRECTIONS = new LinkedHashMap<>();

    static {
        DIRECTIONS.put(
                "edge",
                List.of("периферийные вычисления", "edge computing", "mobile edge computing", "distributed computing", "cs.DC"));
        DIRECTIONS.put("fintech", List.of("финтех", "fintech", "finance", "payments", "mobile payment"));
    }

    @Test
    @DisplayName("агент отвечает по направлениям датасета, и каждая страница становится документом")
    void researchLive() throws IOException {
        assumeTrue(Boolean.getBoolean("horizon.live"), "живая проверка включается -Dhorizon.live=true");
        String nlpUrl = System.getProperty("horizon.nlp.url", "http://127.0.0.1:18086");
        String only = System.getProperty("horizon.live.directions", "");
        Path out = Path.of(System.getProperty("horizon.live.out", "target/deep-research-live"));
        Files.createDirectories(out);
        var research = new DeepResearchProperties(nlpUrl, Duration.ofMinutes(6), 40, 30, 25, null);
        var settings = new ConnectorsProperties.ConnectorSettings(true, null, null, null, null, null, null, null, null);
        var properties = new ConnectorsProperties(
                null, null, null, null, 0, null, Map.of(DeepResearchConnector.SOURCE_ID, settings));

        for (var direction : DIRECTIONS.entrySet()) {
            if (!only.isBlank() && !only.contains(direction.getKey())) {
                continue;
            }
            String name = direction.getKey();
            RawPayloadStore store = (source, hash, fetchedAt, payload, type) -> {
                try {
                    Files.write(out.resolve(name + ".json"), payload);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
                return Optional.of("live/" + name + ".json");
            };
            var connector = new DeepResearchConnector(
                    properties,
                    research,
                    DeepResearchClient.http(nlpUrl),
                    new ObjectMapper(),
                    store,
                    Clock.systemUTC());
            List<String> values = direction.getValue();
            var request = new CollectionRequest(
                    UUID.randomUUID(),
                    values.get(0),
                    values.get(0),
                    "ru",
                    LocalDate.of(2021, 1, 1),
                    LocalDate.of(2026, 9, 27),
                    Set.of(),
                    5000,
                    values.subList(1, values.size()));
            long started = System.nanoTime();
            List<Document> documents;
            try (var stream = connector.collect(request, null)) {
                documents = stream.documents().toList();
            }
            var byClass = new TreeMap<String, Integer>();
            var byHost = new TreeMap<String, Integer>();
            for (Document document : documents) {
                byClass.merge(document.sourceClass().name(), 1, Integer::sum);
                String url = document.identifiers().url();
                byHost.merge(url == null ? "?" : java.net.URI.create(url).getHost(), 1, Integer::sum);
            }
            System.out.printf(
                    "LIVE %s: %d документов за %d с; классы %s; хосты %s%n",
                    name, documents.size(), (System.nanoTime() - started) / 1_000_000_000L, byClass, byHost);
            assertThat(documents).allSatisfy(document -> {
                assertThat(document.provenance().rawRef()).isEqualTo("live/" + name + ".json");
                assertThat(document.provenance().payloadHash()).hasSize(64);
                assertThat(document.abstractText()).isNotBlank();
            });
        }
    }
}
