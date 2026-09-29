package dev.horizon.ingestion.connector.support;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import dev.horizon.ingestion.config.ConnectorsProperties;
import dev.horizon.ingestion.domain.port.RateLimiters;
import dev.horizon.ingestion.domain.port.RawPayloadStore;
import dev.horizon.ingestion.domain.support.Hashing;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * The one place this service talks to the outside world.
 *
 * <p>Every outbound request passes through the same four gates, in this order:
 *
 * <ol>
 *   <li><b>Rate limiter</b> — blocks until the source's budget allows the call (FR-04.4).
 *   <li><b>Retry</b> — bounded attempts with exponential backoff <em>and jitter</em>, and only for
 *       {@link ConnectorException.Retryable}, {@code IOException} and transport errors. A 400 or 404
 *       is never retried: it would waste the budget and cannot succeed.
 *   <li><b>Circuit breaker</b> — per source, so a sustained outage stops us from queueing behind a
 *       dead endpoint; when it opens, the run is marked partial rather than failed (FR-04.6).
 *   <li><b>Raw payload archive</b> — the response bytes are stored and hashed before anything parses
 *       them (FR-04.5, BR-C5).
 * </ol>
 *
 * <p>Retry wraps the breaker, not the other way round: each attempt must be seen by the breaker, and
 * once the breaker opens the remaining attempts fail fast instead of sleeping through the backoff.
 *
 * <p>The registries are built here from {@code horizon.connectors.resilience.*} rather than through
 * Resilience4j's YAML autoconfiguration, because the retry <em>predicate</em> — the "429/5xx/IO
 * only" rule — is the part that matters and it cannot be expressed in properties. One source of
 * truth beats two.
 */
public class ConnectorHttpClient {

    private static final Logger log = LoggerFactory.getLogger(ConnectorHttpClient.class);

    private final WebClient webClient;
    private final RateLimiters rateLimiters;
    private final RawPayloadStore payloadStore;
    private final CircuitBreakerRegistry circuitBreakers;
    private final RetryRegistry retries;
    private final Clock clock;
    private final ConnectorsProperties properties;
    private final MeterRegistry meterRegistry;

    public ConnectorHttpClient(
            WebClient webClient,
            RateLimiters rateLimiters,
            RawPayloadStore payloadStore,
            ConnectorsProperties properties,
            Clock clock,
            MeterRegistry meterRegistry) {
        this.webClient = webClient;
        this.rateLimiters = rateLimiters;
        this.payloadStore = payloadStore;
        this.properties = properties;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
        var resilience = properties.resilience();
        this.circuitBreakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(resilience.slidingWindowSize())
                .minimumNumberOfCalls(resilience.minimumNumberOfCalls())
                .failureRateThreshold(resilience.failureRateThreshold())
                .waitDurationInOpenState(resilience.waitDurationInOpenState())
                .permittedNumberOfCallsInHalfOpenState(resilience.permittedCallsInHalfOpenState())
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                // A permanent 4xx is our bug, not the source's outage: recording it would trip the
                // breaker for a fault retrying can never clear.
                .recordException(ConnectorHttpClient::isUpstreamFailure)
                .build());
        this.retries = RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(resilience.maxAttempts())
                .intervalFunction(IntervalFunction.ofExponentialRandomBackoff(
                        resilience.initialBackoff(),
                        resilience.backoffMultiplier(),
                        resilience.jitterFactor(),
                        resilience.maxBackoff()))
                .retryOnException(ConnectorHttpClient::isUpstreamFailure)
                .build());
    }

    /**
     * Performs a rate-limited, retried, circuit-broken GET and archives the response.
     *
     * @param permitsPerMinute the source's current budget — passed in because an operator can change
     *     it at runtime
     */
    public RawHttpResponse get(String sourceId, URI uri, Map<String, String> headers, int permitsPerMinute) {
        return call(sourceId, uri, headers, null, permitsPerMinute);
    }

    /**
     * То же для POST с телом JSON — у источников, где поиск задаётся телом запроса, а ключ уходит
     * заголовком (Lens). Ключ в адресе попал бы в журнал и в происхождение каждого документа.
     */
    public RawHttpResponse postJson(
            String sourceId, URI uri, Map<String, String> headers, String json, int permitsPerMinute) {
        return call(sourceId, uri, headers, json, permitsPerMinute);
    }

    private RawHttpResponse call(
            String sourceId, URI uri, Map<String, String> headers, String json, int permitsPerMinute) {
        CircuitBreaker breaker = breakerFor(sourceId);
        Retry retry = retries.retry(sourceId);
        Supplier<RawHttpResponse> call = () -> execute(sourceId, uri, headers, json, permitsPerMinute);
        // Предохранитель снаружи повторов: он считает запросы, а не попытки. Изнутри каждая попытка
        // шла в окно отдельно — четыре 503 на один тяжёлый запрос Europe PMC давали четыре отказа,
        // и следующий же успешный ответ открывал предохранитель (4 из 5 при пороге 50%), а
        // следующую страницу источник уже не отдавал: «Не ответили: Europe PMC» (стенд, 29.09).
        Supplier<RawHttpResponse> guarded =
                CircuitBreaker.decorateSupplier(breaker, Retry.decorateSupplier(retry, call));
        // Считается результат вызова целиком — после повторов и предохранителя, — потому что
        // именно он определяет полноту корпуса. Счётчик отдельных попыток показывал бы долю отказов
        // источника, а не долю запросов, которые так и не получили данных.
        try {
            RawHttpResponse response = guarded.get();
            counter(sourceId, "success").increment();
            return response;
        } catch (RuntimeException e) {
            counter(sourceId, "failure").increment();
            throw e;
        }
    }

    /**
     * Предохранитель источника, не старше одного анализа.
     *
     * <p>Предохранитель, открытый в прошлом прогоне, не должен решать за следующий: Europe PMC однажды
     * ответил 503 на тяжёлый запрос, и восемь часов подряд каждый анализ получал «источник не
     * ответил», не сделав к нему ни одного запроса (стенд, 29.09). Открытый дольше
     * {@link #STALE_OPEN} сбрасывается при следующем обращении; переходы — в журнал.
     */
    private CircuitBreaker breakerFor(String sourceId) {
        CircuitBreaker breaker = circuitBreakers.circuitBreaker(sourceId);
        if (openedAt.putIfAbsent(sourceId, java.time.Instant.EPOCH) == null) {
            breaker.getEventPublisher().onStateTransition(event -> {
                log.info("Предохранитель {}: {}", sourceId, event.getStateTransition());
                if (event.getStateTransition().getToState() == CircuitBreaker.State.OPEN) {
                    openedAt.put(sourceId, clock.instant());
                }
            });
        }
        if (breaker.getState() == CircuitBreaker.State.OPEN
                && openedAt.getOrDefault(sourceId, java.time.Instant.EPOCH).plus(STALE_OPEN).isBefore(clock.instant())) {
            log.info("Предохранитель {} открыт дольше {} — сброшен для нового анализа", sourceId, STALE_OPEN);
            breaker.reset();
        }
        return breaker;
    }

    private static final java.time.Duration STALE_OPEN = java.time.Duration.ofMinutes(5);
    private final java.util.concurrent.ConcurrentHashMap<String, java.time.Instant> openedAt =
            new java.util.concurrent.ConcurrentHashMap<>();

    private Counter counter(String sourceId, String outcome) {
        return meterRegistry.counter("horizon.connector.requests", "source", sourceId, "outcome", outcome);
    }

    private RawHttpResponse execute(
            String sourceId, URI uri, Map<String, String> headers, String json, int permitsPerMinute) {
        rateLimiters.forSource(sourceId, permitsPerMinute).acquire();
        var startedAt = clock.instant();
        ResponseEntity<String> response;
        try {
            WebClient.RequestHeadersSpec<?> request = json == null
                    ? webClient.get().uri(uri).headers(httpHeaders -> headers.forEach(httpHeaders::set))
                    : webClient.post()
                            .uri(uri)
                            .headers(httpHeaders -> headers.forEach(httpHeaders::set))
                            .contentType(MediaType.APPLICATION_JSON)
                            .bodyValue(json);
            response = request
                    // exchangeToMono, not retrieve(): a 429 or 503 is a value we must inspect (for
                    // the status, and for Retry-After hints), not an exception thrown by the client.
                    .exchangeToMono(clientResponse -> clientResponse.toEntity(String.class))
                    .block(properties.responseTimeout().plusSeconds(5));
        } catch (WebClientRequestException e) {
            throw new ConnectorException.Retryable(sourceId, 0, "Transport failure for " + safeUri(uri), e);
        } catch (RuntimeException e) {
            if (e instanceof ConnectorException connectorException) {
                throw connectorException;
            }
            throw new ConnectorException.Retryable(sourceId, 0, "Request to " + safeUri(uri) + " failed: " + e, e);
        }
        if (response == null) {
            throw new ConnectorException.Retryable(sourceId, 0, "Empty response from " + safeUri(uri));
        }

        int status = response.getStatusCode().value();
        String body = response.getBody() == null ? "" : response.getBody();
        if (status == 429 || status >= 500) {
            throw new ConnectorException.Retryable(
                    sourceId, status, "%s returned %d for %s".formatted(sourceId, status, safeUri(uri)));
        }
        if (status >= 400) {
            throw new ConnectorException.Permanent(
                    sourceId, status, "%s returned %d for %s".formatted(sourceId, status, safeUri(uri)));
        }

        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        String payloadHash = Hashing.sha256Hex(bytes);
        String rawRef = payloadStore
                .archive(sourceId, payloadHash, startedAt, bytes, contentTypeOf(response))
                .orElse(null);
        if (log.isDebugEnabled()) {
            log.debug("{} {} {} → {} ({} bytes)", sourceId, json == null ? "GET" : "POST", safeUri(uri), status,
                    bytes.length);
        }
        return new RawHttpResponse(sourceId, safeUri(uri), status, body, payloadHash, startedAt, rawRef);
    }

    private static String safeUri(URI uri) {
        return uri.toString().replaceAll("(?i)([?&]api_key=)[^&]+", "$1[redacted]");
    }

    private static String contentTypeOf(ResponseEntity<String> response) {
        var contentType = response.getHeaders().getContentType();
        return contentType == null ? "application/octet-stream" : contentType.toString();
    }

    /** The retry and circuit-breaker predicate: transient upstream trouble only. */
    static boolean isUpstreamFailure(Throwable throwable) {
        if (throwable instanceof ConnectorException connectorException) {
            if ("openalex".equals(connectorException.sourceId()) && connectorException.httpStatus() == 429) {
                return false;
            }
            return connectorException.retryable();
        }
        return throwable instanceof IOException || throwable instanceof WebClientRequestException;
    }

    /** Current breaker state, for diagnostics and tests. */
    public CircuitBreaker.State circuitState(String sourceId) {
        return circuitBreakers.circuitBreaker(sourceId).getState();
    }
}
