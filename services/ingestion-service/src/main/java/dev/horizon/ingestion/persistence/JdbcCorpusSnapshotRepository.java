package dev.horizon.ingestion.persistence;

import java.sql.Array;
import java.sql.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import dev.horizon.ingestion.domain.port.CorpusSnapshotRepository;
import dev.horizon.ingestion.domain.snapshot.CorpusSnapshot;

/**
 * Persists the exact document set an analysis ran on.
 *
 * <p>Insert-only and idempotent on the snapshot id: a redelivered collection command re-derives the
 * same snapshot, and writing it twice must be a no-op rather than a constraint violation.
 */
@Repository
public class JdbcCorpusSnapshotRepository implements CorpusSnapshotRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcCorpusSnapshotRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void save(CorpusSnapshot snapshot, UUID researchRequestId, int attempt) {
        var params = new MapSqlParameterSource()
                .addValue("id", snapshot.id())
                .addValue("researchRequestId", researchRequestId)
                .addValue("attempt", attempt)
                .addValue("normalizedQuery", snapshot.normalizedQuery())
                .addValue("windowFrom", Date.valueOf(snapshot.windowFrom()))
                .addValue("windowTo", Date.valueOf(snapshot.windowTo()))
                .addValue("documentCount", snapshot.documentIds().size())
                .addValue("sourcesUsed", snapshot.sourcesUsed().toArray(String[]::new))
                .addValue("unavailableSources", snapshot.unavailableSources().toArray(String[]::new))
                .addValue("partial", snapshot.partial())
                .addValue("contentHash", snapshot.contentHash());

        int inserted = jdbc.update(
                """
                INSERT INTO corpus_snapshots (id, research_request_id, attempt, normalized_query,
                                              window_from, window_to, document_count,
                                              sources_used, unavailable_sources, partial, content_hash)
                VALUES (:id, :researchRequestId, :attempt, :normalizedQuery,
                        :windowFrom, :windowTo, :documentCount,
                        :sourcesUsed, :unavailableSources, :partial, :contentHash)
                ON CONFLICT (id) DO NOTHING
                """,
                params);

        if (inserted == 0) {
            return; // already stored — snapshots are immutable
        }

        List<UUID> ids = snapshot.documentIds();
        SqlParameterSource[] batch = new SqlParameterSource[ids.size()];
        for (int i = 0; i < ids.size(); i++) {
            batch[i] = new MapSqlParameterSource()
                    .addValue("snapshotId", snapshot.id())
                    .addValue("documentId", ids.get(i))
                    .addValue("ordinal", i);
        }
        if (batch.length > 0) {
            jdbc.batchUpdate(
                    """
                    INSERT INTO corpus_snapshot_documents (snapshot_id, document_id, ordinal)
                    VALUES (:snapshotId, :documentId, :ordinal)
                    ON CONFLICT DO NOTHING
                    """,
                    batch);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CorpusSnapshot> findById(UUID snapshotId) {
        return jdbc
                .query("SELECT * FROM corpus_snapshots WHERE id = :id", Map.of("id", snapshotId), mapper(snapshotId))
                .stream()
                .findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CorpusSnapshot> findByRequestAndAttempt(UUID researchRequestId, int attempt) {
        return jdbc
                .query(
                        "SELECT * FROM corpus_snapshots WHERE research_request_id = :requestId AND attempt = :attempt",
                        new MapSqlParameterSource()
                                .addValue("requestId", researchRequestId)
                                .addValue("attempt", attempt),
                        (rs, rowNum) -> rs.getObject("id", UUID.class))
                .stream()
                .findFirst()
                .flatMap(this::findById);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> findDocumentIds(UUID snapshotId, int offset, int limit) {
        return jdbc.query(
                """
                SELECT document_id FROM corpus_snapshot_documents
                WHERE snapshot_id = :id
                ORDER BY ordinal
                LIMIT :limit OFFSET :offset
                """,
                new MapSqlParameterSource()
                        .addValue("id", snapshotId)
                        .addValue("limit", Math.min(Math.max(limit, 1), 10_000))
                        .addValue("offset", Math.max(offset, 0)),
                (rs, rowNum) -> rs.getObject("document_id", UUID.class));
    }

    private RowMapper<CorpusSnapshot> mapper(UUID snapshotId) {
        return (rs, rowNum) -> new CorpusSnapshot(
                rs.getObject("id", UUID.class),
                rs.getString("normalized_query"),
                rs.getDate("window_from").toLocalDate(),
                rs.getDate("window_to").toLocalDate(),
                findDocumentIds(snapshotId, 0, 10_000),
                stringArray(rs.getArray("sources_used")),
                stringArray(rs.getArray("unavailable_sources")),
                rs.getBoolean("partial"),
                rs.getString("content_hash"));
    }

    private static List<String> stringArray(Array array) throws java.sql.SQLException {
        if (array == null) {
            return List.of();
        }
        Object value = array.getArray();
        return value instanceof String[] values ? List.of(values) : List.of();
    }
}
