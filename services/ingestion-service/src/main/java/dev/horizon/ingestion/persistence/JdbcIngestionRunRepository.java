package dev.horizon.ingestion.persistence;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import dev.horizon.ingestion.domain.port.IngestionRunRepository;
import dev.horizon.ingestion.domain.port.PageResult;
import dev.horizon.ingestion.domain.run.Cursor;
import dev.horizon.ingestion.domain.run.IngestionRun;
import dev.horizon.ingestion.domain.run.RunCounters;
import dev.horizon.ingestion.domain.run.RunMode;
import dev.horizon.ingestion.domain.run.RunStatus;

/** Run history: the audit trail of what was collected, when, and how it ended. */
@Repository
public class JdbcIngestionRunRepository implements IngestionRunRepository {

    private static final String COLUMNS =
            """
            id, source_id, mode, research_request_id, query, window_from, window_to, status,
            cursor_before, cursor_before_date, cursor_after, cursor_after_date,
            documents_fetched, documents_created, documents_duplicate, documents_rejected,
            error_code, error_message, started_at, finished_at, trace_id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcIngestionRunRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public IngestionRun save(IngestionRun run) {
        var params = new MapSqlParameterSource()
                .addValue("id", run.id())
                .addValue("sourceId", run.sourceId())
                .addValue("mode", run.mode().name())
                .addValue("researchRequestId", run.researchRequestId())
                .addValue("query", run.query())
                .addValue("windowFrom", run.windowFrom() == null ? null : Date.valueOf(run.windowFrom()))
                .addValue("windowTo", run.windowTo() == null ? null : Date.valueOf(run.windowTo()))
                .addValue("status", run.status().name())
                .addValue("cursorBefore", run.cursorBefore().value())
                .addValue(
                        "cursorBeforeDate",
                        run.cursorBefore().lastPublishedOn() == null
                                ? null
                                : Date.valueOf(run.cursorBefore().lastPublishedOn()))
                .addValue("cursorAfter", run.cursorAfter().map(Cursor::value).orElse(null))
                .addValue(
                        "cursorAfterDate",
                        run.cursorAfter()
                                .map(Cursor::lastPublishedOn)
                                .map(Date::valueOf)
                                .orElse(null))
                .addValue("fetched", run.counters().fetched())
                .addValue("created", run.counters().created())
                .addValue("duplicates", run.counters().duplicates())
                .addValue("rejected", run.counters().rejected())
                .addValue("errorCode", run.errorCode())
                .addValue("errorMessage", run.errorMessage())
                .addValue("startedAt", Timestamp.from(run.startedAt()))
                .addValue("finishedAt", run.finishedAt() == null ? null : Timestamp.from(run.finishedAt()))
                .addValue("traceId", run.traceId());

        jdbc.update(
                """
                INSERT INTO ingestion_runs (
                    id, source_id, mode, research_request_id, query, window_from, window_to, status,
                    cursor_before, cursor_before_date, cursor_after, cursor_after_date,
                    documents_fetched, documents_created, documents_duplicate, documents_rejected,
                    error_code, error_message, started_at, finished_at, trace_id)
                VALUES (
                    :id, :sourceId, :mode, :researchRequestId, :query, :windowFrom, :windowTo, :status,
                    :cursorBefore, :cursorBeforeDate, :cursorAfter, :cursorAfterDate,
                    :fetched, :created, :duplicates, :rejected,
                    :errorCode, :errorMessage, :startedAt, :finishedAt, :traceId)
                ON CONFLICT (id) DO UPDATE SET
                    status = EXCLUDED.status,
                    cursor_after = EXCLUDED.cursor_after,
                    cursor_after_date = EXCLUDED.cursor_after_date,
                    documents_fetched = EXCLUDED.documents_fetched,
                    documents_created = EXCLUDED.documents_created,
                    documents_duplicate = EXCLUDED.documents_duplicate,
                    documents_rejected = EXCLUDED.documents_rejected,
                    error_code = EXCLUDED.error_code,
                    error_message = EXCLUDED.error_message,
                    finished_at = EXCLUDED.finished_at
                """,
                params);
        return run;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<IngestionRun> findById(UUID id) {
        return jdbc
                .query("SELECT " + COLUMNS + " FROM ingestion_runs WHERE id = :id", Map.of("id", id), mapper())
                .stream()
                .findFirst();
    }

    /**
     * Whether a run for this source is already in flight.
     *
     * <p>{@code startedAfter} bounds the question deliberately: a run that started days ago and never
     * finished is a crashed replica, not a live competitor, and must not block collection forever.
     */
    @Override
    @Transactional(readOnly = true)
    public boolean existsRunning(String sourceId, Instant startedAfter) {
        Boolean exists = jdbc.queryForObject(
                """
                SELECT EXISTS(
                    SELECT 1 FROM ingestion_runs
                    WHERE source_id = :sourceId AND status = 'RUNNING' AND started_at >= :startedAfter)
                """,
                new MapSqlParameterSource()
                        .addValue("sourceId", sourceId)
                        .addValue("startedAfter", Timestamp.from(startedAfter)),
                Boolean.class);
        return Boolean.TRUE.equals(exists);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<IngestionRun> findLatest(String sourceId) {
        return jdbc
                .query(
                        "SELECT " + COLUMNS
                                + " FROM ingestion_runs WHERE source_id = :sourceId ORDER BY started_at DESC LIMIT 1",
                        Map.of("sourceId", sourceId),
                        mapper())
                .stream()
                .findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<IngestionRun> findPage(String sourceId, int page, int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        int safePage = Math.max(page, 0);
        var params = new MapSqlParameterSource()
                .addValue("sourceId", sourceId)
                .addValue("limit", safeSize)
                .addValue("offset", (long) safePage * safeSize);

        String filter = sourceId == null ? "" : " WHERE source_id = :sourceId";
        List<IngestionRun> content = jdbc.query(
                "SELECT " + COLUMNS + " FROM ingestion_runs" + filter
                        + " ORDER BY started_at DESC LIMIT :limit OFFSET :offset",
                params,
                mapper());
        Long total = jdbc.queryForObject("SELECT count(*) FROM ingestion_runs" + filter, params, Long.class);
        return new PageResult<>(content, safePage, safeSize, total == null ? 0L : total);
    }

    private RowMapper<IngestionRun> mapper() {
        return (rs, rowNum) -> IngestionRun.rehydrate(
                rs.getObject("id", UUID.class),
                rs.getString("source_id"),
                RunMode.valueOf(rs.getString("mode")),
                rs.getObject("research_request_id", UUID.class),
                rs.getString("query"),
                localDate(rs, "window_from"),
                localDate(rs, "window_to"),
                cursor(rs, "cursor_before", "cursor_before_date"),
                cursor(rs, "cursor_after", "cursor_after_date"),
                RunStatus.valueOf(rs.getString("status")),
                new RunCounters(
                        rs.getInt("documents_fetched"),
                        rs.getInt("documents_created"),
                        rs.getInt("documents_duplicate"),
                        rs.getInt("documents_rejected")),
                rs.getString("error_code"),
                rs.getString("error_message"),
                instant(rs, "started_at"),
                instant(rs, "finished_at"),
                rs.getString("trace_id"));
    }

    private static java.time.LocalDate localDate(ResultSet rs, String column) throws SQLException {
        Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Cursor cursor(ResultSet rs, String valueColumn, String dateColumn) throws SQLException {
        String value = rs.getString(valueColumn);
        java.time.LocalDate date = localDate(rs, dateColumn);
        if (value == null && date == null) {
            return Cursor.start();
        }
        return new Cursor(value, date);
    }

    @SuppressWarnings("unused")
    private static final int UNUSED_TYPES_REFERENCE = Types.NULL;
}
