package dev.horizon.ingestion.domain.port;

import java.time.Instant;
import java.util.Optional;

import dev.horizon.ingestion.domain.run.Cursor;

/**
 * Where each source's incremental crawl left off (FR-04.7).
 *
 * <p>Written only after a page has been committed — see {@code IngestionRun#commitPage}.
 */
public interface SourceCursorRepository {

    Optional<Cursor> find(String sourceId);

    void save(String sourceId, Cursor cursor, Instant updatedAt);
}
