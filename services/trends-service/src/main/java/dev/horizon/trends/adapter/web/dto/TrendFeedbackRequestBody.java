package dev.horizon.trends.adapter.web.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import dev.horizon.trends.domain.feedback.TrendFeedback;

/** Body of {@code PUT /api/v1/reports/{reportId}/trends/{trendKey}/feedback}. */
public record TrendFeedbackRequestBody(@NotNull TrendFeedback.Verdict verdict, @Size(max = 2000) String comment) {}
