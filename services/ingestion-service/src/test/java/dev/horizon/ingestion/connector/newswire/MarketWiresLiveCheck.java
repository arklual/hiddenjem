package dev.horizon.ingestion.connector.newswire;

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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.horizon.ingestion.config.ConnectorConfiguration;
import dev.horizon.ingestion.config.ConnectorsProperties;
import dev.horizon.ingestion.connector.producthunt.ProductHuntConnector;
import dev.horizon.ingestion.connector.support.AbstractSourceConnector;
import dev.horizon.ingestion.connector.support.ConnectorHttpClient;
import dev.horizon.ingestion.connector.support.InMemoryRateLimiters;
import dev.horizon.ingestion.connector.support.NoopRawPayloadStore;
import dev.horizon.ingestion.domain.document.Author;
import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.port.CollectionRequest;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Живая проверка источников рыночной стадии — GlobeNewswire, PR Newswire, Product Hunt: настоящая
 * сеть, ни одной подмены.
 *
 * <p>В обычном прогоне пропускается. Запуск — {@code ./mvnw -pl ingestion-service test
 * -Dtest=MarketWiresLiveCheck -Dhorizon.live=true -Dsurefire.failIfNoSpecifiedTests=false};
 * {@code -Dhorizon.live.sources=globenewswire,prnewswire,producthunt} сужает набор. Печатает по
 * каждому запросу и источнику: сколько документов, за сколько секунд, диапазон дат, число
 * организаций и отказал ли источник — ноль и отказ обязаны различаться (разбор 101).
 */
class MarketWiresLiveCheck {

    private static final List<String> QUERIES =
            List.of("neuromorphic computing", "stablecoin payments", "edge AI", "fintech");

    @Test
    @DisplayName("ленты релизов и запусков отвечают на запросы")
    void collectLive() {
        assumeTrue(Boolean.getBoolean("horizon.live"), "живая проверка включается -Dhorizon.live=true");
        String sources = System.getProperty("horizon.live.sources", "globenewswire,prnewswire,producthunt");
        String email = System.getenv().getOrDefault("HORIZON_CONNECTOR_CONTACT_EMAIL", "");
        var settings =
                new ConnectorsProperties.ConnectorSettings(true, null, null, null, null, null, null, List.of(), null);
        var properties = new ConnectorsProperties(
                "HorizonBot/1.0",
                email,
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                0,
                null,
                Map.of(
                        GlobeNewswireConnector.SOURCE_ID, settings,
                        PrNewswireConnector.SOURCE_ID, settings,
                        ProductHuntConnector.SOURCE_ID, settings));
        var http = new ConnectorHttpClient(
                new ConnectorConfiguration().connectorWebClient(properties),
                new InMemoryRateLimiters(1),
                new NoopRawPayloadStore(),
                properties,
                Clock.systemUTC(),
                new SimpleMeterRegistry());
        Map<String, AbstractSourceConnector<?>> connectors = new LinkedHashMap<>();
        if (sources.contains(GlobeNewswireConnector.SOURCE_ID)) {
            connectors.put(GlobeNewswireConnector.SOURCE_ID, new GlobeNewswireConnector(properties, http));
        }
        if (sources.contains(PrNewswireConnector.SOURCE_ID)) {
            connectors.put(PrNewswireConnector.SOURCE_ID, new PrNewswireConnector(properties, http));
        }
        if (sources.contains(ProductHuntConnector.SOURCE_ID)) {
            connectors.put(ProductHuntConnector.SOURCE_ID, new ProductHuntConnector(properties, http));
        }

        LocalDate today = LocalDate.now();
        int answered = 0;
        for (String query : QUERIES) {
            var request = new CollectionRequest(
                    UUID.randomUUID(),
                    query,
                    query,
                    "en",
                    LocalDate.of(today.getYear() - 6, 1, 1),
                    today,
                    Set.of(),
                    5000,
                    List.of());
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
                long withAbstract = documents.stream()
                        .filter(document -> document.abstractText() != null
                                && !document.abstractText().isBlank())
                        .count();
                System.out.printf(
                        "LIVE %-24s %-14s %s документов %4d  с анонсом %4d  за %6.1f с  даты %s..%s  организаций %d%s%n",
                        query,
                        entry.getKey(),
                        failure == null ? "ok   " : "ОТКАЗ",
                        documents.size(),
                        withAbstract,
                        seconds,
                        oldest,
                        newest,
                        organizations,
                        failure == null ? "" : "  " + failure);
                documents.stream()
                        .limit(2)
                        .forEach(document -> System.out.printf(
                                "     %s  %s  [%s]  %s%n",
                                document.publishedOn(),
                                document.title(),
                                document.authors().isEmpty()
                                        ? ""
                                        : document.authors().get(0).organizationName(),
                                document.identifiers().url()));
                if (failure == null) {
                    answered++;
                }
            }
        }
        assertThat(answered).as("хоть один источник ответил").isPositive();
    }
}
