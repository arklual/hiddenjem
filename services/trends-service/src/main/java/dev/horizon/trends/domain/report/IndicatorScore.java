package dev.horizon.trends.domain.report;

import java.util.Map;

import dev.horizon.platform.common.util.Guards;

/**
 * One indicator's contribution to the emergence score (methodology §3–4).
 *
 * <p>{@code multiplier = value^weight} and the score is {@code 100 × ∏ multiplier}, so the
 * multiplier reads directly as "this indicator multiplied the score by X" — the formulation analysts
 * actually understand. {@code shortfallShare} answers the complementary question: which indicator is
 * holding the score back the most.
 *
 * <p>Values are computed by the analytics engine (ADR-0016); this context validates and stores them
 * but never recomputes them.
 */
public record IndicatorScore(
        String name,
        double value,
        double weight,
        double multiplier,
        double shortfallShare,
        String explanation,
        Map<String, Object> diagnostics) {

    public IndicatorScore {
        Guards.requireText(name, "indicator.name");
        Guards.requireRange(value, "indicator.value", 0.0, 1.0);
        Guards.requireRange(weight, "indicator.weight", 0.0, 1.0);
        Guards.requireRange(multiplier, "indicator.multiplier", 0.0, 1.0);
        Guards.requireRange(shortfallShare, "indicator.shortfallShare", 0.0, 1.0);
        diagnostics = diagnostics == null ? Map.of() : Map.copyOf(diagnostics);
    }
}
