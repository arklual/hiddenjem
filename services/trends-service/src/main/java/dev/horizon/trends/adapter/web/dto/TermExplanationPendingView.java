package dev.horizon.trends.adapter.web.dto;

/**
 * Трассировка ещё считается: движок повторяет анализ, ответ будет по тому же адресу.
 *
 * @param state всегда {@code RUNNING} — готовый ответ приходит другим телом и статусом 200
 * @param retryAfterSeconds через сколько секунд спросить снова; то же, что в {@code Retry-After}
 */
public record TermExplanationPendingView(String state, int retryAfterSeconds) {}
