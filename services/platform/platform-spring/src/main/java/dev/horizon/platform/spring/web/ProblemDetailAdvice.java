package dev.horizon.platform.spring.web;

import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import dev.horizon.platform.common.error.FieldViolation;
import dev.horizon.platform.common.error.HorizonException;
import dev.horizon.platform.common.error.ProblemType;

/**
 * Translates exceptions into RFC 9457 {@code application/problem+json} responses (ADR-0013).
 *
 * <p>Design rule: only {@link HorizonException} carries a client-safe message. Everything else is
 * reported as {@code internal-error} with a generic detail — an unmapped exception can therefore
 * never leak a stack trace, SQL fragment or upstream URL to a caller. The correlating {@code traceId}
 * is always returned so an operator can find the full context in the logs.
 */
@RestControllerAdvice
public class ProblemDetailAdvice {

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailAdvice.class);

    private final Clock clock;

    public ProblemDetailAdvice(Clock clock) {
        this.clock = clock;
    }

    /**
     * Сколько ждать после отказа по лимиту.
     *
     * <p>Точное окно знает тот, кто ограничивает: у шлюза это минута, у ограничителя входов —
     * настраиваемая величина. Сюда оно пока не доходит, а заголовок обязан быть: контракт объявляет
     * {@code Retry-After} у каждого 429, и клиент без него не знает, повторять через секунду или
     * через час. Минута — наименьшее из разумных: повторивший раньше срока получит тот же 429, что
     * честнее молчания.
     */
    private static final String RETRY_AFTER_SECONDS = "60";

    @ExceptionHandler(HorizonException.class)
    public ProblemDetail handleHorizon(HorizonException ex, HttpServletRequest request, HttpServletResponse response) {
        if (ex.type().status() >= 500) {
            log.error("Business failure {} on {}", ex.type().slug(), request.getRequestURI(), ex);
        } else {
            log.debug("Business failure {} on {}: {}", ex.type().slug(), request.getRequestURI(), ex.getMessage());
        }
        // Отказ по лимиту приходит двумя путями: фильтр шлюза заголовок ставит, ограничитель входов
        // в iam бросает исключение — и до этой правки его 429 уходил без Retry-After, вопреки
        // контракту. Проверка e2e это и поймала, первым же прогоном.
        if (ex.type() == ProblemType.RATE_LIMITED && !response.containsHeader(HttpHeaders.RETRY_AFTER)) {
            response.setHeader(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
        }
        return build(ex.type(), ex.getMessage(), request, ex.violations());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleBeanValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new FieldViolation(fe.getField(), fe.getDefaultMessage(), fe.getRejectedValue()))
                .toList();
        return build(ProblemType.VALIDATION_ERROR, "Запрос не прошёл валидацию", request, violations);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        List<FieldViolation> violations =
                ex.getConstraintViolations().stream().map(this::toViolation).toList();
        return build(ProblemType.VALIDATION_ERROR, "Запрос не прошёл валидацию", request, violations);
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MissingServletRequestParameterException.class,
        MethodArgumentTypeMismatchException.class
    })
    public ProblemDetail handleMalformed(Exception ex, HttpServletRequest request) {
        return build(ProblemType.MALFORMED_REQUEST, "Тело или параметры запроса некорректны", request, List.of());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        return build(ProblemType.VALIDATION_ERROR, ex.getMessage(), request, List.of());
    }

    /**
     * Неизвестный путь — это 404, а не сбой службы.
     *
     * <p>Обработчик рядом с {@link NoHandlerFoundException} нужен потому, что Spring 6.1 бросает
     * при непопадании в контроллер уже другое исключение: путь уходит обработчику статики, и тот
     * сообщает {@code NoResourceFoundException}. Оно перехватывалось общим правилом ниже и
     * превращалось в 500 с {@code retryable: true} — клиенту предлагалось повторить запрос,
     * который не станет верным никогда, а в журнал на каждую опечатку падал ERROR со стеком.
     */
    @ExceptionHandler({NoHandlerFoundException.class, NoResourceFoundException.class})
    public ProblemDetail handleNoHandler(Exception ex, HttpServletRequest request) {
        return build(ProblemType.NOT_FOUND, "Ресурс не найден", request, List.of());
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(ProblemType.INTERNAL_ERROR, "Внутренняя ошибка сервиса", request, List.of());
    }

    private FieldViolation toViolation(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        int lastDot = path.lastIndexOf('.');
        return new FieldViolation(
                lastDot >= 0 ? path.substring(lastDot + 1) : path, violation.getMessage(), violation.getInvalidValue());
    }

    private ProblemDetail build(
            ProblemType type, String detail, HttpServletRequest request, List<FieldViolation> violations) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.valueOf(type.status()));
        problem.setType(URI.create(type.uri()));
        problem.setTitle(type.title());
        problem.setDetail(detail);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("timestamp", clock.instant());
        problem.setProperty("retryable", type.retryable());
        problem.setProperty("traceId", TraceIds.current());
        if (!violations.isEmpty()) {
            problem.setProperty(
                    "errors",
                    violations.stream()
                            .map(v -> Map.of(
                                    "field", v.field(),
                                    "message", v.message() == null ? "" : v.message(),
                                    "rejectedValue", String.valueOf(v.rejectedValue())))
                            .toList());
        }
        return problem;
    }
}
