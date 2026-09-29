package dev.horizon.ingestion.connector.edgar;

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

import dev.horizon.ingestion.config.ConnectorConfiguration;
import dev.horizon.ingestion.config.ConnectorsProperties;
import dev.horizon.ingestion.connector.support.AbstractSourceConnector;
import dev.horizon.ingestion.connector.support.ConnectorHttpClient;
import dev.horizon.ingestion.connector.support.InMemoryRateLimiters;
import dev.horizon.ingestion.connector.support.NoopRawPayloadStore;
import dev.horizon.ingestion.domain.document.Author;
import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.port.CollectionRequest;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Живая проверка SEC EDGAR: настоящая сеть, ни одной подмены.
 *
 * <p>В обычном прогоне пропускается. Запуск — {@code ./mvnw -pl ingestion-service test
 * -Dtest=EdgarLiveCheck -Dhorizon.live=true -Dsurefire.failIfNoSpecifiedTests=false}. Печатает по
 * каждому запросу: сколько подач, за сколько секунд, диапазон дат, сколько форм D и S-1, сколько
 * компаний — и отказал ли источник: ноль и отказ обязаны различаться.
 */
public class EdgarLiveCheck {

    public static final List<String> QUERIES =
            List.of("quantum computing", "neuromorphic computing", "post-quantum cryptography", "stablecoin");

    @Test
    @DisplayName("EDGAR отвечает на четыре запроса")
    void collectLive() {
        assumeTrue(Boolean.getBoolean("horizon.live"), "живая проверка включается -Dhorizon.live=true");
        int answered = run(EdgarConnector.SOURCE_ID, 6, EdgarConnector::new, QUERIES);
        assertThat(answered).as("хоть один запрос выполнен").isPositive();
    }

    /**
     * Общий прогон, им пользуется и {@code IetfLiveCheck}: окно — шесть последних лет, как у живой
     * проверки продуктовых источников.
     */
    public static int run(
            String sourceId,
            int requestsPerMinute,
            TriFunction<ConnectorsProperties, ConnectorHttpClient, ObjectMapper, AbstractSourceConnector<?>> factory,
            List<String> queries) {
        String email = System.getenv().getOrDefault("HORIZON_CONNECTOR_CONTACT_EMAIL", "");
        var properties = new ConnectorsProperties(
                "HorizonBot/1.0",
                email,
                Duration.ofSeconds(5),
                Duration.ofSeconds(60),
                0,
                null,
                Map.of(
                        sourceId,
                        new ConnectorsProperties.ConnectorSettings(
                                true, null, requestsPerMinute, null, null, null, null, List.of(), null)));
        var http = new ConnectorHttpClient(
                new ConnectorConfiguration().connectorWebClient(properties),
                new InMemoryRateLimiters(1),
                new NoopRawPayloadStore(),
                properties,
                Clock.systemUTC(),
                new SimpleMeterRegistry());
        AbstractSourceConnector<?> connector = factory.apply(properties, http, new ObjectMapper());
        LocalDate today = LocalDate.now();
        int answered = 0;
        for (String query : queries) {
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
            int rejected = 0;
            try (var stream = connector.collect(request, null)) {
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
                    .filter(name -> name != null && !name.isBlank())
                    .distinct()
                    .count();
            Map<String, Long> kinds = new java.util.TreeMap<>();
            documents.forEach(d -> kinds.merge(d.title().split(" — ")[0], 1L, Long::sum));
            System.out.printf(
                    "LIVE %-7s %-28s %s документов %4d  отвергнуто %d  за %6.1f с  даты %s..%s  организаций %d%s%s%n",
                    sourceId,
                    query,
                    failure == null ? "ok   " : "ОТКАЗ",
                    documents.size(),
                    rejected,
                    seconds,
                    oldest,
                    newest,
                    organizations,
                    sourceId.equals(EdgarConnector.SOURCE_ID) ? "  формы " + kinds : "",
                    failure == null ? "" : "  " + failure);
            documents.stream()
                    .limit(3)
                    .forEach(d -> System.out.printf(
                            "     %s  %s  %s%n",
                            d.publishedOn(), d.title(), d.identifiers().url()));
            if (failure == null) {
                answered++;
            }
        }
        return answered;
    }

    @FunctionalInterface
    public interface TriFunction<A, B, C, R> {
        R apply(A a, B b, C c);
    }
}
