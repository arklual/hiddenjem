package dev.horizon.ingestion.connector.sbir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizon.ingestion.config.ConnectorConfiguration;
import dev.horizon.ingestion.config.ConnectorsProperties;
import dev.horizon.ingestion.connector.nsf.NsfConnector;
import dev.horizon.ingestion.connector.support.AbstractSourceConnector;
import dev.horizon.ingestion.connector.support.ConnectorHttpClient;
import dev.horizon.ingestion.connector.support.InMemoryRateLimiters;
import dev.horizon.ingestion.connector.support.NoopRawPayloadStore;
import dev.horizon.ingestion.domain.document.Author;
import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.port.CollectionRequest;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Живая проверка грантовых реестров — SBIR.gov и NSF Award Search: настоящая сеть, ни одной подмены.
 *
 * <p>В обычном прогоне пропускается. Запуск — {@code ./mvnw -pl ingestion-service test
 * -Dtest=GrantSourcesLiveCheck -Dhorizon.live=true -Dsurefire.failIfNoSpecifiedTests=false};
 * {@code -Dhorizon.live.sources=sbir} или {@code nsf} — один источник. Печатает по каждому запросу и
 * источнику: сколько документов, за сколько секунд, диапазон дат, сколько организаций и каких
 * типов, и отказал ли источник — ноль и отказ обязаны различаться.
 */
class GrantSourcesLiveCheck {

    private static final List<String> QUERIES =
            List.of("neuromorphic computing", "quantum sensing", "stablecoin payments");

    @Test
    @DisplayName("SBIR.gov и NSF отвечают на запросы ранних технологий")
    void collectLive() {
        assumeTrue(Boolean.getBoolean("horizon.live"), "живая проверка включается -Dhorizon.live=true");
        String sources = System.getProperty("horizon.live.sources", "sbir,nsf");
        String email = System.getenv().getOrDefault("HORIZON_CONNECTOR_CONTACT_EMAIL", "");
        var properties = new ConnectorsProperties(
                "HorizonBot/1.0", email, Duration.ofSeconds(5), Duration.ofSeconds(30), 0, null, Map.of());
        var http = new ConnectorHttpClient(
                new ConnectorConfiguration().connectorWebClient(properties),
                new InMemoryRateLimiters(1),
                new NoopRawPayloadStore(),
                properties,
                Clock.systemUTC(),
                new SimpleMeterRegistry());
        Map<String, AbstractSourceConnector<?>> connectors = new LinkedHashMap<>();
        if (sources.contains("sbir")) {
            connectors.put(SbirConnector.SOURCE_ID, new SbirConnector(properties, http));
        }
        if (sources.contains("nsf")) {
            connectors.put(NsfConnector.SOURCE_ID, new NsfConnector(properties, http, new ObjectMapper()));
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
                int rejected = 0;
                try (var stream = entry.getValue().collect(request, null)) {
                    stream.documents().forEach(documents::add);
                    rejected = stream.rejected();
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
                        .filter(Objects::nonNull)
                        .distinct()
                        .count();
                Map<String, Long> types = documents.stream()
                        .map(document -> document.authors().get(0).organizationType())
                        .collect(Collectors.groupingBy(String::valueOf, TreeMap::new, Collectors.counting()));
                long distinct = documents.stream()
                        .map(document -> document.externalRef().externalId())
                        .distinct()
                        .count();
                System.out.printf(
                        "LIVE %-24s %-5s %s документов %4d (различных %4d, отбраковано %d)  за %6.1f с  даты %s..%s  организаций %d  типы получателей %s%s%n",
                        query,
                        entry.getKey(),
                        failure == null ? "ok   " : "ОТКАЗ",
                        documents.size(),
                        distinct,
                        rejected,
                        seconds,
                        oldest,
                        newest,
                        organizations,
                        types,
                        failure == null ? "" : "  " + failure);
                documents.stream()
                        .limit(3)
                        .forEach(document -> System.out.printf(
                                "     %s  %s  %s  [%s]%n",
                                document.publishedOn(),
                                document.identifiers().url(),
                                document.title().length() > 80
                                        ? document.title().substring(0, 80)
                                        : document.title(),
                                document.venue() == null ? "" : document.venue().name()));
                if (failure == null) {
                    answered++;
                }
            }
        }
        assertThat(answered).as("хоть один источник ответил").isPositive();
    }
}
