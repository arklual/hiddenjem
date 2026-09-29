package dev.horizon.trends.domain.methodology;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import dev.horizon.platform.common.util.Guards;

/**
 * A named, versioned set of weights and parameters for the emergence methodology (BR-B8).
 *
 * <p>Immutable: editing a profile creates a new version. Reports reference the exact profile they
 * were computed with, so changing weights can never retroactively alter a past conclusion.
 *
 * <p>The weight-sum invariant is enforced here, at the only place profiles are created, rather than
 * being re-checked in the analytics engine and the API — one rule, one home.
 */
public record MethodologyProfile(
        UUID id,
        String name,
        int version,
        String methodologyVersion,
        ScoreAggregator aggregator,
        Map<String, Double> weights,
        Map<String, Object> parameters,
        double confidenceThreshold,
        boolean isDefault,
        UUID createdBy,
        Instant createdAt) {

    private static final double WEIGHT_TOLERANCE = 1e-6;

    public MethodologyProfile {
        Guards.requireNonNull(id, "profile.id");
        Guards.requireLength(name, "profile.name", 2, 80);
        Guards.requireArgument(version >= 1, "profile.version must be at least 1");
        Guards.requireText(methodologyVersion, "profile.methodologyVersion");
        Guards.requireNonNull(aggregator, "profile.aggregator");
        Guards.requireNotEmpty(weights == null ? null : weights.keySet(), "profile.weights");
        Guards.requireRange(confidenceThreshold, "profile.confidenceThreshold", 0.0, 1.0);

        var normalized = new LinkedHashMap<String, Double>();
        weights.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> {
            Guards.requireRange(e.getValue(), "weight[" + e.getKey() + "]", 0.0, 1.0);
            // Нулевой вес запрещён, и это не придирка к оформлению. BRULE-4 — «нулевой индикатор
            // обнуляет балл» — держится на произведении: x^w. При w = 0 множитель равен единице
            // независимо от x, и обнуляющее правило для этого индикатора молча выключается. Профиль
            // с weakness = 0 снял бы гарантию «сигнал ещё не мейнстрим», не сказав об этом никому.
            //
            // Намерение «этот индикатор для моей области почти не важен» выражается малым весом, и
            // оно остаётся доступным. Невыразимым становится только намерение отключить правило —
            // потому что отключалось оно не намеренно, а как побочный эффект.
            Guards.requireArgument(
                    e.getValue() > 0.0,
                    "Вес индикатора '%s' должен быть больше нуля: нулевой вес отключает правило BRULE-4"
                            .formatted(e.getKey()));
            normalized.put(e.getKey(), e.getValue());
        });
        double sum =
                normalized.values().stream().mapToDouble(Double::doubleValue).sum();
        if (Math.abs(sum - 1.0) > WEIGHT_TOLERANCE) {
            throw new IllegalArgumentException(
                    "Сумма весов индикаторов должна равняться 1, получено %.9f".formatted(sum));
        }
        weights = Map.copyOf(normalized);
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
    }

    /** Aggregation strategy (methodology §4). Geometric mean means indicators cannot compensate. */
    public enum ScoreAggregator {
        WEIGHTED_GEOMETRIC,
        WEIGHTED_ARITHMETIC,
        MIN_BOUND
    }

    public static Map<String, Double> defaultWeights() {
        return Map.of(
                "novelty", 0.20,
                "growth", 0.30,
                "diffusion", 0.15,
                "weakness", 0.15,
                "coherence", 0.10,
                "impact", 0.10);
    }

    public static Map<String, Object> defaultParameters() {
        return Map.of(
                "tau",
                3.0,
                "growthMax",
                3.0,
                "timeWeight",
                0.05,
                "orgRef",
                50,
                "venueRef",
                25,
                "citationsPerYearRef",
                10,
                "burstThreshold",
                2.0,
                "relevanceThreshold",
                0.25,
                "minDocuments",
                2,
                "minOrganizations",
                2);
    }
}
