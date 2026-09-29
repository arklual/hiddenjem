package dev.horizon.trends.domain.research;

import java.util.List;

/**
 * What the collection step actually managed to gather.
 *
 * <p>Held on the request because the saga needs it later, at report-assembly time: the report must
 * state which sources contributed and which were unavailable, and that knowledge is only produced by
 * the earlier collection step (BRULE-8).
 */
public record CorpusCoverage(int documentCount, List<String> sourcesUsed, List<String> unavailableSources) {

    public CorpusCoverage {
        sourcesUsed = sourcesUsed == null ? List.of() : List.copyOf(sourcesUsed);
        unavailableSources = unavailableSources == null ? List.of() : List.copyOf(unavailableSources);
    }

    public static CorpusCoverage empty() {
        return new CorpusCoverage(0, List.of(), List.of());
    }

    public boolean partial() {
        return !unavailableSources.isEmpty();
    }
}
