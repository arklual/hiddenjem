package dev.horizon.ingestion.connector.uspto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizon.ingestion.config.ConnectorsProperties;
import dev.horizon.ingestion.connector.support.NoopRawPayloadStore;
import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.port.CollectionRequest;

/**
 * Живая проверка Patent Public Search: настоящая сеть, ни одной подмены.
 *
 * <p>В обычном прогоне пропускается. Запуск — {@code ./mvnw -pl ingestion-service test
 * -Dtest=UsptoLiveCheck -Dhorizon.live=true -Dsurefire.failIfNoSpecifiedTests=false}. Шесть запросов
 * в минуту, поэтому четыре формулировки идут около двух минут. Печатает по каждой: сколько
 * документов, диапазон дат, сколько правообладателей — и отказал ли источник.
 */
class UsptoLiveCheck {

    private static final List<String> QUERIES =
            List.of("neuromorphic computing", "quantum sensing", "stablecoin", "solid-state battery");

    @Test
    @DisplayName("USPTO отвечает на четыре запроса")
    void collectLive() {
        assumeTrue(Boolean.getBoolean("horizon.live"), "живая проверка включается -Dhorizon.live=true");
        var properties = new ConnectorsProperties(
                "HorizonBot/1.0",
                "",
                Duration.ofSeconds(5),
                Duration.ofSeconds(60),
                0,
                null,
                Map.of(
                        UsptoConnector.SOURCE_ID,
                        new ConnectorsProperties.ConnectorSettings(
                                true, null, 6, null, null, null, null, List.of(), null)));
        var connector =
                new UsptoConnector(properties, new ObjectMapper(), new NoopRawPayloadStore(), Clock.systemUTC());
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
            long started = System.nanoTime();
            List<Document> documents = new ArrayList<>();
            String failure = null;
            try (var stream = connector.collect(request, null)) {
                stream.documents().forEach(documents::add);
            } catch (RuntimeException e) {
                failure = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            long organizations = documents.stream()
                    .flatMap(d -> d.authors().stream())
                    .map(a -> a.organizationName())
                    .filter(name -> name != null)
                    .distinct()
                    .count();
            System.out.printf(
                    "USPTO «%s»: документов %d за %.1f с, даты %s..%s, организаций %d%s%n",
                    query,
                    documents.size(),
                    (System.nanoTime() - started) / 1e9,
                    documents.stream().map(Document::publishedOn).min(LocalDate::compareTo).orElse(null),
                    documents.stream().map(Document::publishedOn).max(LocalDate::compareTo).orElse(null),
                    organizations,
                    failure == null ? "" : " — ОТКАЗ: " + failure);
            documents.stream().limit(2).forEach(d -> System.out.printf(
                    "    %s | %s | %s%n", d.publishedOn(), d.title(), d.identifiers().url()));
            if (failure == null) {
                answered++;
            }
        }
        assertThat(answered).as("хоть один запрос выполнен").isPositive();
    }
}
