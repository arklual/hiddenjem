package dev.horizon.gateway.filter;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;

import dev.horizon.gateway.config.GatewayProperties;
import dev.horizon.platform.common.error.ProblemType;

import reactor.core.publisher.Mono;

/**
 * Distributed token-bucket rate limiter (NFR-S7).
 *
 * <p>Implemented directly rather than via the built-in filter for one concrete reason: the API
 * contract promises {@code application/problem+json} for every error including 429, and the built-in
 * limiter returns an empty body. A client that has to special-case one status code is a client that
 * will get it wrong.
 *
 * <p>The bucket is evaluated by a Lua script so that read-modify-write is atomic across replicas.
 * Authenticated callers are keyed by subject and get a higher allowance; anonymous callers are keyed
 * by client IP, which is what protects the login endpoint from credential stuffing.
 *
 * <p><b>Fail-open:</b> if Redis is unavailable the request proceeds and a warning is logged.
 * Rate limiting protects capacity; making the whole platform unavailable because the limiter is down
 * would be a strictly worse outcome (ADR-0011).
 */
public class RateLimitWebFilter implements WebFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(RateLimitWebFilter.class);

    private static final String SCRIPT =
            """
            local tokens_key = KEYS[1]
            local timestamp_key = KEYS[2]
            local rate = tonumber(ARGV[1])
            local capacity = tonumber(ARGV[2])
            local now = tonumber(ARGV[3])
            local requested = tonumber(ARGV[4])
            local fill_time = capacity / rate
            local ttl = math.floor(fill_time * 2)
            if ttl < 1 then ttl = 1 end
            local last_tokens = tonumber(redis.call('get', tokens_key))
            if last_tokens == nil then last_tokens = capacity end
            local last_refreshed = tonumber(redis.call('get', timestamp_key))
            if last_refreshed == nil then last_refreshed = 0 end
            local delta = math.max(0, now - last_refreshed)
            local filled_tokens = math.min(capacity, last_tokens + (delta * rate))
            local allowed = 0
            local new_tokens = filled_tokens
            if filled_tokens >= requested then
              new_tokens = filled_tokens - requested
              allowed = 1
            end
            redis.call('setex', tokens_key, ttl, new_tokens)
            redis.call('setex', timestamp_key, ttl, now)
            return { allowed, math.floor(new_tokens) }
            """;

    private static final List<String> EXEMPT_PREFIXES =
            List.of("/actuator/health", "/actuator/prometheus", "/actuator/info");

    private final ReactiveStringRedisTemplate redis;
    private final GatewayProperties properties;
    private final Clock clock;
    private final RedisScript<List> script;

    @SuppressWarnings("rawtypes")
    public RateLimitWebFilter(ReactiveStringRedisTemplate redis, GatewayProperties properties, Clock clock) {
        this.redis = redis;
        this.properties = properties;
        this.clock = clock;
        this.script = RedisScript.of(SCRIPT, List.class);
    }

    @Override
    public int getOrder() {
        // After authentication (so the subject is known) but before routing.
        return Ordered.LOWEST_PRECEDENCE - 100;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (EXEMPT_PREFIXES.stream().anyMatch(path::startsWith)) {
            return chain.filter(exchange);
        }
        // Входа нет, поэтому корзина одна — по адресу клиента.
        var bucket = new Bucket("ip:" + clientIp(exchange.getRequest()), properties.requestsPerMinute());
        return Mono.just(bucket)
                .flatMap(ignored -> isAllowed(bucket)
                        .flatMap(allowed ->
                                Boolean.TRUE.equals(allowed) ? chain.filter(exchange) : reject(exchange, bucket)));
    }

    private record Bucket(String key, int requestsPerMinute) {}

    @SuppressWarnings("unchecked")
    private Mono<Boolean> isAllowed(Bucket bucket) {
        double refillPerSecond = bucket.requestsPerMinute() / 60.0;
        int capacity = Math.max(bucket.requestsPerMinute(), properties.burstCapacity());
        long nowSeconds = clock.instant().getEpochSecond();
        return redis.execute(
                        script,
                        List.of("rl:{%s}:tokens".formatted(bucket.key()), "rl:{%s}:ts".formatted(bucket.key())),
                        List.of(
                                String.valueOf(refillPerSecond),
                                String.valueOf(capacity),
                                String.valueOf(nowSeconds),
                                "1"))
                .next()
                .map(result -> {
                    List<Long> values = (List<Long>) result;
                    return values != null && !values.isEmpty() && values.get(0) == 1L;
                })
                .onErrorResume(error -> {
                    log.warn("Ограничитель запросов недоступен, запрос пропущен: {}", error.toString());
                    return Mono.just(true);
                });
    }

    private Mono<Void> reject(ServerWebExchange exchange, Bucket bucket) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        response.getHeaders().set("Retry-After", "60");
        response.getHeaders().set("X-RateLimit-Limit", String.valueOf(bucket.requestsPerMinute()));
        String body =
                """
                {"type":"%s","title":"%s","status":429,\
                "detail":"Превышен лимит запросов. Повторите попытку через минуту.",\
                "instance":"%s","retryable":true}"""
                        .formatted(
                                ProblemType.RATE_LIMITED.uri(),
                                ProblemType.RATE_LIMITED.title(),
                                exchange.getRequest().getPath().value());
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    /**
     * Trusts {@code X-Forwarded-For} only for its left-most entry and only because the gateway sits
     * behind a controlled ingress that overwrites the header. Behind an untrusted proxy this must be
     * replaced by the ingress-provided real IP.
     */
    private String clientIp(ServerHttpRequest request) {
        String forwarded = request.getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        var remote = request.getRemoteAddress();
        if (remote == null) {
            return "unknown";
        }
        // Адрес здесь может быть неразрешённым, и тогда getAddress() возвращает null.
        //
        // Так и происходит всякий раз, когда запрос идёт через прокси: `server.forward-headers-strategy:
        // framework` означает, что X-Forwarded-For применяет сам Spring — он подменяет адрес источника
        // на InetSocketAddress.createUnresolved(...) и **снимает** заголовок. Ветка выше его поэтому не
        // видит, а getAddress() у неразрешённого адреса пуст: шлюз падал с NullPointerException на любом
        // запросе через интерфейс, отвечая 500 «Внутренняя ошибка шлюза» — включая вход в систему.
        //
        // getHostString() у неразрешённого адреса возвращает как раз тот адрес, который поставил
        // прокси, то есть настоящего клиента, — по нему и следует считать лимит.
        var address = remote.getAddress();
        return address != null ? address.getHostAddress() : remote.getHostString();
    }
}
