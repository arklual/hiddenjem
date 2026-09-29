package dev.horizon.ingestion.domain.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import dev.horizon.ingestion.domain.run.IngestionRun;

/** Persistence port for the {@link IngestionRun} aggregate. */
public interface IngestionRunRepository {

    IngestionRun save(IngestionRun run);

    Optional<IngestionRun> findById(UUID id);

    /**
     * Whether a run for this source started after {@code startedAfter} is still {@code RUNNING}.
     *
     * <p>The time bound matters: a process killed mid-run leaves a {@code RUNNING} row behind, and
     * without a staleness horizon that row would block the source forever.
     */
    boolean existsRunning(String sourceId, Instant startedAfter);

    Optional<IngestionRun> findLatest(String sourceId);

    PageResult<IngestionRun> findPage(String sourceId, int page, int size);
}
