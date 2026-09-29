package dev.horizon.ingestion.application;

import java.util.List;
import java.util.UUID;

import dev.horizon.ingestion.domain.run.RunCounters;

/**
 * What one connector contributed to a collection.
 *
 * <p>Three outcomes, all normal: {@code succeeded} (possibly with zero documents),
 * {@code unavailable} (disabled, missing credentials, unsupported request) and {@code failed}
 * (network, circuit breaker, upstream error). Only the first counts as a used source; the other two
 * land in {@code unavailableSources} and make the corpus partial (BR-C7, BRULE-8).
 *
 * <p>Documents already persisted by a run that later failed are still reported: they are real
 * documents that matched the query, and dropping them would waste evidence the user can see is
 * incomplete anyway.
 */
public record CollectionOutcome(
        String sourceId,
        UUID runId,
        boolean succeeded,
        boolean available,
        List<UUID> documentIds,
        RunCounters counters,
        String errorCode,
        String errorMessage) {

    public CollectionOutcome {
        documentIds = List.copyOf(documentIds);
        counters = counters == null ? RunCounters.ZERO : counters;
    }

    public static CollectionOutcome succeeded(
            String sourceId, UUID runId, List<UUID> documentIds, RunCounters counters) {
        return new CollectionOutcome(sourceId, runId, true, true, documentIds, counters, null, null);
    }

    public static CollectionOutcome unavailable(String sourceId, String reason) {
        return new CollectionOutcome(
                sourceId, null, false, false, List.of(), RunCounters.ZERO, "SOURCE_UNAVAILABLE", reason);
    }

    public static CollectionOutcome failed(
            String sourceId, UUID runId, List<UUID> documentIds, RunCounters counters, String code, String message) {
        return new CollectionOutcome(sourceId, runId, false, true, documentIds, counters, code, message);
    }

    public boolean contributedDocuments() {
        return !documentIds.isEmpty();
    }
}
