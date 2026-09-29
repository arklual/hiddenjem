package dev.horizon.ingestion.connector.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;

import dev.horizon.ingestion.config.ConnectorsProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import reactor.core.publisher.Mono;

/**
 * Предохранитель считает запросы, а не попытки повтора (стенд, 29.09): один тяжёлый запрос Europe PMC
 * с четырьмя ответами 503 открывал предохранитель на следующем же успешном ответе, и источник
 * выпадал из отчёта.
 */
class ConnectorHttpClientBreakerTest {

    @Test
    @DisplayName("Один запрос, исчерпавший повторы, не открывает предохранитель источника")
    void oneExhaustedRequestDoesNotOpenTheBreaker() {
        AtomicInteger calls = new AtomicInteger();
        WebClient web = WebClient.builder()
                .exchangeFunction(request -> Mono.just(calls.incrementAndGet() <= 4
                        ? ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build()
                        : ClientResponse.create(HttpStatus.OK).body("{}").build()))
                .build();
        var resilience = new ConnectorsProperties.Resilience(
                4, Duration.ofMillis(1), Duration.ofMillis(2), 2.0, 0.1, 20, 5, 50f, Duration.ofSeconds(30), 1);
        var properties = new ConnectorsProperties(null, null, null, null, 0, resilience, Map.of());
        var http = new ConnectorHttpClient(
                web, new InMemoryRateLimiters(100), new NoopRawPayloadStore(), properties, Clock.systemUTC(),
                new SimpleMeterRegistry());
        URI uri = URI.create("https://example.org/search");

        assertThatThrownBy(() -> http.get("europepmc", uri, Map.of(), 6000))
                .isInstanceOf(ConnectorException.Retryable.class);
        for (int page = 0; page < 5; page++) {
            assertThat(http.get("europepmc", uri, Map.of(), 6000)).isNotNull();
        }
        assertThat(calls.get()).isEqualTo(9);
    }
}
