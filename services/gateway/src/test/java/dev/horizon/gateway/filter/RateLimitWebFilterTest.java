package dev.horizon.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.net.InetSocketAddress;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import dev.horizon.gateway.config.GatewayProperties;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RateLimitWebFilterTest {

    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-08-05T10:00:00Z"), ZoneOffset.UTC);

    @Mock
    private ReactiveStringRedisTemplate redis;

    private final GatewayProperties properties = new GatewayProperties(null, null, 300, 60);

    @Test
    @DisplayName("lets the request through when the bucket has tokens")
    void allowsWhenTokensAvailable() {
        stubScript(List.of(1L, 41L));
        var filter = new RateLimitWebFilter(redis, properties, FIXED);
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/reports/x").build());

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty())).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("returns 429 with a problem+json body when the bucket is empty")
    void rejectsWhenBucketEmpty() {
        stubScript(List.of(0L, 0L));
        var filter = new RateLimitWebFilter(redis, properties, FIXED);
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/reports/x").build());

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty())).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(exchange.getResponse().getHeaders().getFirst("Retry-After")).isEqualTo("60");
        assertThat(exchange.getResponse().getHeaders().getContentType()).hasToString("application/problem+json");
    }

    @Test
    @DisplayName("не падает, когда адрес источника неразрешён — так его отдаёт прокси")
    void survivesUnresolvedRemoteAddress() {
        stubScript(List.of(1L, 41L));
        var filter = new RateLimitWebFilter(redis, properties, FIXED);
        // Ровно то, что подставляет Spring, применив X-Forwarded-For: адрес неразрешён,
        // getAddress() у него пуст, а сам заголовок к этому моменту уже снят. Шлюз падал здесь
        // с NullPointerException и отвечал 500 на каждый запрос, идущий через интерфейс.
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/reports/x")
                .remoteAddress(InetSocketAddress.createUnresolved("203.0.113.7", 51234))
                .build());

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty())).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("fails open: a Redis outage must not take the platform down")
    void failsOpenWhenRedisUnavailable() {
        when(redis.execute(any(RedisScript.class), any(List.class), any(List.class)))
                .thenReturn(Flux.error(new IllegalStateException("redis down")));
        var filter = new RateLimitWebFilter(redis, properties, FIXED);
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/reports/x").build());

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty())).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    @DisplayName("never rate-limits health probes — a throttled probe would cause a restart loop")
    void skipsActuatorProbes() {
        var filter = new RateLimitWebFilter(redis, properties, FIXED);
        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/actuator/health/readiness").build());

        StepVerifier.create(filter.filter(exchange, ex -> Mono.empty())).verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubScript(List<Long> result) {
        when(redis.execute(any(RedisScript.class), any(List.class), any(List.class)))
                .thenReturn(Flux.just((List) result));
    }
}
