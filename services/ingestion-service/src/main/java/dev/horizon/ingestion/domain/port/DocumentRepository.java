package dev.horizon.ingestion.domain.port;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import dev.horizon.ingestion.domain.document.DedupKey;
import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.document.ExternalRef;

/**
 * Persistence port for the {@link Document} aggregate (Dependency Inversion: the domain declares
 * what it needs, the JPA adapter provides it).
 *
 * <p>Lookups are by {@code (ref, publishedOn)} and {@code (dedupKey, publishedOn)} rather than by id
 * alone because {@code documents} is partitioned by publication date: including the partition key
 * turns a scan of every partition into a single index probe, and it matches the unique constraints
 * that ultimately enforce deduplication.
 */
public interface DocumentRepository {

    /** Existing document with the same source-local identity, if any. */
    Optional<UUID> findIdByExternalRef(ExternalRef ref, LocalDate publishedOn);

    /** Existing document representing the same work, possibly from another source (FR-04.3). */
    Optional<UUID> findIdByDedupKey(DedupKey dedupKey, LocalDate publishedOn);

    /** Inserts a new document together with its authors and raw topics. */
    UUID save(Document document);

    long countBySourceId(String sourceId);
}
