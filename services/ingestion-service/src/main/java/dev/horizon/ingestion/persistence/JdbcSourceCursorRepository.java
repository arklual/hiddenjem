package dev.horizon.ingestion.persistence;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import dev.horizon.ingestion.domain.port.SourceCursorRepository;
import dev.horizon.ingestion.domain.run.Cursor;

/**
 * Incremental-collection watermarks.
 *
 * <p>The cursor is advanced only after a page has been committed (enforced by
 * {@code IngestionRun.commitPage}), so a crash mid-page re-reads that page rather than skipping it.
 * Re-reading is harmless because ingestion is idempotent on {@code (sourceId, externalId)}; skipping
 * would silently lose documents.
 */
@Repository
public class JdbcSourceCursorRepository implements SourceCursorRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcSourceCursorRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Cursor> find(String sourceId) {
        return jdbc
                .query(
                        "SELECT cursor_value, last_published_on FROM source_cursors WHERE source_id = :id",
                        Map.of("id", sourceId),
                        (rs, rowNum) -> {
                            Date date = rs.getDate("last_published_on");
                            return new Cursor(rs.getString("cursor_value"), date == null ? null : date.toLocalDate());
                        })
                .stream()
                .findFirst();
    }

    @Override
    @Transactional
    public void save(String sourceId, Cursor cursor, Instant updatedAt) {
        LocalDate lastPublishedOn = cursor == null ? null : cursor.lastPublishedOn();
        var params = new MapSqlParameterSource()
                .addValue("id", sourceId)
                .addValue("value", cursor == null ? null : cursor.value())
                .addValue("lastPublishedOn", lastPublishedOn == null ? null : Date.valueOf(lastPublishedOn))
                .addValue("updatedAt", Timestamp.from(updatedAt));

        jdbc.update(
                """
                INSERT INTO source_cursors (source_id, cursor_value, last_published_on, updated_at, version)
                VALUES (:id, :value, :lastPublishedOn, :updatedAt, 0)
                ON CONFLICT (source_id) DO UPDATE SET
                    cursor_value = EXCLUDED.cursor_value,
                    last_published_on = EXCLUDED.last_published_on,
                    updated_at = EXCLUDED.updated_at,
                    version = source_cursors.version + 1
                """,
                params);
    }
}
