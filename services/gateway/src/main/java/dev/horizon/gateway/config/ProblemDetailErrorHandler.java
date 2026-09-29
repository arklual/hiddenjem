package dev.horizon.gateway.config;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizon.platform.common.error.ProblemType;

import reactor.core.publisher.Mono;

/**
 * Renders gateway-level failures (routing errors, unreachable upstreams, invalid tokens) in the same
 * RFC 9457 shape the services use (ADR-0013).
 *
 * <p>Without this, a client would see two different error formats depending on whether a request got
 * past the edge — the sort of inconsistency that produces defensive, unreliable client code.
 */
@Component
@Order(-2)
public class ProblemDetailErrorHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailErrorHandler.class);

    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ProblemDetailErrorHandler(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        var response = exchange.getResponse();
        if (response.isCommitted()) {
            return Mono.error(ex);
        }
        ProblemType type = classify(ex);
        HttpStatus status = HttpStatus.valueOf(type.status());
        if (status.is5xxServerError()) {
            log.error("Ошибка шлюза на {}", exchange.getRequest().getPath(), ex);
        }

        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);

        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", type.uri());
        problem.put("title", type.title());
        problem.put("status", type.status());
        problem.put("detail", detailFor(type));
        problem.put("instance", exchange.getRequest().getPath().value());
        problem.put("timestamp", clock.instant().toString());
        problem.put("retryable", type.retryable());

        try {
            var buffer = response.bufferFactory().wrap(objectMapper.writeValueAsBytes(problem));
            return response.writeWith(Mono.just(buffer));
        } catch (Exception serializationFailure) {
            var buffer = response.bufferFactory()
                    .wrap("{\"title\":\"Internal error\",\"status\":500}".getBytes(StandardCharsets.UTF_8));
            return response.writeWith(Mono.just(buffer));
        }
    }

    private ProblemType classify(Throwable ex) {
        if (ex instanceof ResponseStatusException rse) {
            return switch (rse.getStatusCode().value()) {
                case 401, 403 -> ProblemType.ACCESS_DENIED;
                case 404 -> ProblemType.NOT_FOUND;
                case 429 -> ProblemType.RATE_LIMITED;
                case 502, 503, 504 -> ProblemType.UPSTREAM_UNAVAILABLE;
                default -> ProblemType.INTERNAL_ERROR;
            };
        }
        if (ex instanceof java.net.ConnectException
                || ex instanceof java.util.concurrent.TimeoutException
                || ex instanceof io.netty.channel.ConnectTimeoutException) {
            return ProblemType.UPSTREAM_UNAVAILABLE;
        }
        return ProblemType.INTERNAL_ERROR;
    }

    private String detailFor(ProblemType type) {
        return switch (type) {
            case UPSTREAM_UNAVAILABLE -> "Сервис временно недоступен. Повторите попытку позже.";
            case ACCESS_DENIED -> "Доступ к ресурсу закрыт.";
            case NOT_FOUND -> "Запрошенный ресурс не найден.";
            case RATE_LIMITED -> "Превышен лимит запросов.";
            default -> "Внутренняя ошибка шлюза.";
        };
    }
}
