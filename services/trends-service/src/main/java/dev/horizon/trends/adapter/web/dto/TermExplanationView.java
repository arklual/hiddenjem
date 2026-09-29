package dev.horizon.trends.adapter.web.dto;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

import dev.horizon.trends.application.port.TermTraceExplainer;

/**
 * OpenAPI {@code TermExplanation} — what happened to the terms an analyst expected to see.
 *
 * <p>{@code stages} travels with every answer rather than being published once: the client draws
 * the journey from it, and a client holding its own copy would keep drawing the old journey on the
 * day the pipeline gains a stage — silently, and looking correct.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TermExplanationView(List<String> stages, List<TraceView> traces) {

    public record TraceView(
            String term,
            String canonicalTerm,
            String stage,
            String outcome,
            String reason,
            Map<String, Object> detail,
            boolean inReport) {}

    public static TermExplanationView from(TermTraceExplainer.Explanation explanation) {
        return new TermExplanationView(
                List.copyOf(explanation.stages()),
                explanation.traces().stream()
                        .map(trace -> new TraceView(
                                trace.term(),
                                trace.canonicalTerm(),
                                trace.stage(),
                                trace.outcome(),
                                trace.reason(),
                                trace.detail(),
                                trace.inReport()))
                        .toList());
    }
}
