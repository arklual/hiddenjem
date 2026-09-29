package dev.horizon.trends.adapter.web.dto;

/** OpenAPI {@code FailureInfo}. */
public record FailureInfoView(String code, String message, boolean retryable) {}
