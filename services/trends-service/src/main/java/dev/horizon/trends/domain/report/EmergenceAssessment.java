package dev.horizon.trends.domain.report;

import java.util.List;

import dev.horizon.platform.common.util.Guards;

/**
 * The verdict on one trend, with the full decomposition that justifies it.
 *
 * <p>Invariant J4: indicator weights sum to 1. Violating it would silently change the meaning of the
 * score, so it is checked on construction rather than trusted.
 */
public record EmergenceAssessment(
        double score,
        double confidence,
        boolean lowEvidence,
        List<IndicatorScore> indicators,
        RankStability rankStability) {

    /**
     * Вердикт без измерения устойчивости места.
     *
     * <p>Отчёты, выпущенные до появления §16, диапазона не несут, и отсутствие обязано читаться как
     * «не измеряли», а не как «место неустойчиво». Разница существенна: ложная оговорка о шаткости
     * обесценивает настоящие.
     */
    public EmergenceAssessment(double score, double confidence, boolean lowEvidence, List<IndicatorScore> indicators) {
        this(score, confidence, lowEvidence, indicators, null);
    }

    private static final double WEIGHT_SUM_TOLERANCE = 1e-6;

    public EmergenceAssessment {
        Guards.requireRange(score, "emergenceScore", 0.0, 100.0);
        Guards.requireRange(confidence, "confidence", 0.0, 1.0);
        Guards.requireNotEmpty(indicators, "indicators");
        indicators = List.copyOf(indicators);
        double weightSum =
                indicators.stream().mapToDouble(IndicatorScore::weight).sum();
        if (Math.abs(weightSum - 1.0) > WEIGHT_SUM_TOLERANCE) {
            throw new IllegalArgumentException(
                    "J4 нарушен: сумма весов индикаторов должна равняться 1, получено %.9f".formatted(weightSum));
        }
    }

    /** Диапазон места, если он измерялся. */
    public java.util.Optional<RankStability> rankStabilityOptional() {
        return java.util.Optional.ofNullable(rankStability);
    }

    /** The indicator that limits the score the most — what the analyst should look at first. */
    public IndicatorScore limitingIndicator() {
        return indicators.stream()
                .max((a, b) -> Double.compare(a.shortfallShare(), b.shortfallShare()))
                .orElseThrow();
    }
}
