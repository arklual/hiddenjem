package dev.horizon.platform.common.error;

/**
 * Catalogue of problem types (ADR-0013, RFC 9457).
 *
 * <p>Single source of truth shared by every service and mirrored by the frontend. The {@code slug}
 * becomes the {@code type} URI; the frontend switches on it, so slugs are part of the public
 * contract and must not be renamed without a version bump.
 */
public enum ProblemType {
    VALIDATION_ERROR("validation-error", "Ошибка валидации запроса", 400, false),
    MALFORMED_REQUEST("malformed-request", "Некорректный запрос", 400, false),
    ACCESS_DENIED("access-denied", "Недостаточно прав", 403, false),
    NOT_FOUND("not-found", "Ресурс не найден", 404, false),
    CONFLICT("conflict", "Конфликт состояния", 409, false),
    ILLEGAL_STATE_TRANSITION("illegal-state-transition", "Недопустимый переход состояния", 409, false),
    QUOTA_EXCEEDED("quota-exceeded", "Превышена квота запросов", 429, true),
    RATE_LIMITED("rate-limited", "Слишком много запросов", 429, true),
    UPSTREAM_UNAVAILABLE("upstream-unavailable", "Внешний источник недоступен", 502, true),
    ANALYSIS_FAILED("analysis-failed", "Анализ не был завершён", 500, true),
    INTERNAL_ERROR("internal-error", "Внутренняя ошибка сервиса", 500, true);

    private static final String BASE_URI = "https://horizon.dev/problems/";

    private final String slug;
    private final String title;
    private final int status;
    private final boolean retryable;

    ProblemType(String slug, String title, int status, boolean retryable) {
        this.slug = slug;
        this.title = title;
        this.status = status;
        this.retryable = retryable;
    }

    public String slug() {
        return slug;
    }

    public String uri() {
        return BASE_URI + slug;
    }

    public String title() {
        return title;
    }

    public int status() {
        return status;
    }

    public boolean retryable() {
        return retryable;
    }
}
