package dev.horizon.trends.adapter.web.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

/** OpenAPI {@code AnalysisProgress}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AnalysisProgressView(String stage, int percent, String message, Instant updatedAt) {}
