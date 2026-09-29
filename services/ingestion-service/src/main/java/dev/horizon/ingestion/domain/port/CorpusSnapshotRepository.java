package dev.horizon.ingestion.domain.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import dev.horizon.ingestion.domain.snapshot.CorpusSnapshot;

/**
 * Storage for corpus snapshots.
 *
 * <p>A snapshot is the exact document set an analysis ran on. Persisting it is what turns
 * "reproducible in principle" into "reproducible in practice" (ADR-0015): the analytics engine
 * resolves a {@code snapshotId} to the same documents on every replay, months later, even after the
 * corpus has grown.
 *
 * <p>Snapshots are immutable — {@link #save} is insert-only and idempotent on the snapshot id.
 */
public interface CorpusSnapshotRepository {

    void save(CorpusSnapshot snapshot, UUID researchRequestId, int attempt);

    Optional<CorpusSnapshot> findById(UUID snapshotId);

    /** Document ids of the snapshot, in the stable order that defines its content hash. */
    List<UUID> findDocumentIds(UUID snapshotId, int offset, int limit);

    Optional<CorpusSnapshot> findByRequestAndAttempt(UUID researchRequestId, int attempt);
}
