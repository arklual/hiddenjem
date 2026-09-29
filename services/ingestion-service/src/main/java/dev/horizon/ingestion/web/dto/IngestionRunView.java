package dev.horizon.ingestion.web.dto;

import java.time.Instant;
import java.util.UUID;

import dev.horizon.ingestion.domain.run.IngestionRun;

/** Wire shape of a run, matching {@code IngestionRunView} in the OpenAPI contract. */
public record IngestionRunView(
        UUID id,
        String sourceId,
        String mode,
        String status,
        int documentsFetched,
        int documentsCreated,
        int documentsDuplicate,
        int documentsRejected,
        String errorCode,
        String errorMessage,
        Instant startedAt,
        Instant finishedAt) {

    public static IngestionRunView from(IngestionRun run) {
        return new IngestionRunView(
                run.id(),
                run.sourceId(),
                run.mode().name(),
                run.status().name(),
                run.counters().fetched(),
                run.counters().created(),
                run.counters().duplicates(),
                run.counters().rejected(),
                run.errorCode(),
                run.errorMessage(),
                run.startedAt(),
                run.finishedAt());
    }
}
