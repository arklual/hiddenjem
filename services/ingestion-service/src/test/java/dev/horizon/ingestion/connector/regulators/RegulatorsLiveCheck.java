package dev.horizon.ingestion.connector.regulators;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.horizon.ingestion.config.ConnectorConfiguration;
import dev.horizon.ingestion.config.ConnectorsProperties;
import dev.horizon.ingestion.connector.support.ConnectorHttpClient;
import dev.horizon.ingestion.connector.support.InMemoryRateLimiters;
import dev.horizon.ingestion.connector.support.NoopRawPayloadStore;
import dev.horizon.ingestion.domain.document.Author;
import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.port.CollectionRequest;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Живая проверка источника {@code regulators}: настоящая сеть, настоящие ответы BIS, FCA и Банка
 * России, ни одной подмены.
 *
 * <p>В обычном прогоне пропускается. Запуск — {@code ./mvnw -pl ingestion-service test
 * -Dtest=RegulatorsLiveCheck -Dhorizon.live=true -Dsurefire.failIfNoSpecifiedTests=false}. Один
 * экземпляр коннектора на все запросы, как в сервисе: первый запрос обходит площадки (около двух
 * минут — страницы BIS идут с паузой в три секунды), следующие сопоставляют уже прочитанное.
 * Печатает по каждому запросу, сколько документов и какие: площадка, дата, организация, заголовок.
 */
class RegulatorsLiveCheck {

    /** Запрос → цели словаря; пустой список — запрос уходит как есть. */
    private static final Map<String, List<String>> QUERIES = new LinkedHashMap<>();

    static {
        QUERIES.put("stablecoin", List.of());
        QUERIES.put("central bank digital currency", List.of());
        QUERIES.put("tokenization", List.of());
        QUERIES.put("цифровой рубль", List.of("digital ruble", "central bank digital currency"));
        QUERIES.put("open banking", List.of());
        QUERIES.put("edge AI", List.of());
    }

    @Test
    @DisplayName("BIS, FCA и Банк России отвечают на финтех-запросы и молчат на чужие")
    void collectLive() {
        assumeTrue(Boolean.getBoolean("horizon.live"), "живая проверка включается -Dhorizon.live=true");
        String email = System.getenv().getOrDefault("HORIZON_CONNECTOR_CONTACT_EMAIL", "");
        var properties = new ConnectorsProperties(
                "HorizonBot/1.0",
                email,
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                0,
                null,
                Map.of(
                        RegulatorsConnector.SOURCE_ID,
                        new ConnectorsProperties.ConnectorSettings(
                                true, null, 20, null, null, null, null, RegulatorsConnector.DEFAULT_FEEDS, null)));
        var http = new ConnectorHttpClient(
                new ConnectorConfiguration().connectorWebClient(properties),
                new InMemoryRateLimiters(1),
                new NoopRawPayloadStore(),
                properties,
                Clock.systemUTC(),
                new SimpleMeterRegistry());
        var connector = new RegulatorsConnector(properties, http);
        LocalDate today = LocalDate.now();
        int answered = 0;
        for (var query : QUERIES.entrySet()) {
            var request = new CollectionRequest(
                    UUID.randomUUID(),
                    query.getKey(),
                    query.getKey(),
                    "ru",
                    LocalDate.of(today.getYear() - 6, 1, 1),
                    today,
                    Set.of(),
                    5000,
                    query.getValue());
            long started = System.nanoTime();
            List<Document> documents = new ArrayList<>();
            String failure = null;
            try (var stream = connector.collect(request, null)) {
                stream.documents().forEach(documents::add);
            } catch (RuntimeException e) {
                failure = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            double seconds = (System.nanoTime() - started) / 1e9;
            Map<String, Long> bySite = documents.stream()
                    .collect(Collectors.groupingBy(d -> d.venue().name(), LinkedHashMap::new, Collectors.counting()));
            System.out.printf(
                    "LIVE %-32s %s документов %3d за %6.1f с  %s%s%n",
                    query.getKey(),
                    failure == null ? "ok   " : "ОТКАЗ",
                    documents.size(),
                    seconds,
                    bySite,
                    failure == null ? "" : "  " + failure);
            for (Document document : documents) {
                System.out.printf(
                        "      %s  %-2s  %-40.40s  %.110s  %s%n",
                        document.publishedOn(),
                        document.language(),
                        document.authors().stream()
                                .map(Author::organizationName)
                                .collect(Collectors.joining(", ")),
                        document.title(),
                        document.identifiers().url());
            }
            if (failure == null) {
                answered++;
            }
        }
        assertThat(answered).as("источник ответил хоть на один запрос").isPositive();
    }
}
