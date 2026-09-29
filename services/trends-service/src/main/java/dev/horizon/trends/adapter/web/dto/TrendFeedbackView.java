package dev.horizon.trends.adapter.web.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

import dev.horizon.trends.domain.feedback.TrendFeedback;

/** OpenAPI {@code TrendFeedbackView}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TrendFeedbackView(String verdict, String comment, Instant createdAt, boolean carried) {

    /**
     * @param carried the verdict was given on an earlier version of the same direction, not here.
     *     Shown as such rather than as a fresh mark: a judgement the product placed on the analyst's
     *     behalf is indistinguishable from one they made, and they would find their name on
     *     something they never said.
     */
    public static TrendFeedbackView from(TrendFeedback feedback, boolean carried) {
        return new TrendFeedbackView(feedback.verdict().name(), feedback.comment(), feedback.createdAt(), carried);
    }
}
