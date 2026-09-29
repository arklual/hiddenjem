package dev.horizon.trends.adapter.persistence;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import dev.horizon.trends.application.port.UnrecognizedDirectionJournal;
import dev.horizon.trends.domain.direction.UnrecognizedDirection;

/**
 * Журнал нераспознанных направлений поверх Postgres.
 *
 * <p>Одна формулировка — одна строка со счётчиком, а не строка на каждый запрос: очередь пополнения
 * словаря сортируется по спросу, и протокол вместо счётчика пришлось бы группировать при каждом
 * чтении. Слияние делает сама база через {@code ON CONFLICT}, поэтому два одновременных отчёта по
 * одной формулировке дают два инкремента, а не потерянное обновление.
 *
 * <p>{@code first_seen} при конфликте не трогается: он отвечает на вопрос «как давно это
 * спрашивают», и обновить его значило бы стереть возраст задачи при каждом новом обращении.
 */
@Repository
public class JdbcUnrecognizedDirectionJournal implements UnrecognizedDirectionJournal {

    private static final String UPSERT =
            """
            INSERT INTO unrecognized_directions
                (organization_id, normalized_query, raw_query, occurrences, suggestions, first_seen, last_seen)
            VALUES (:organizationId, :normalizedQuery, :rawQuery, 1, :suggestions, :seenAt, :seenAt)
            ON CONFLICT (organization_id, normalized_query) DO UPDATE SET
                occurrences = unrecognized_directions.occurrences + 1,
                last_seen   = EXCLUDED.last_seen,
                raw_query   = EXCLUDED.raw_query,
                suggestions = EXCLUDED.suggestions
            """;

    private static final String MOST_ASKED =
            """
            SELECT * FROM unrecognized_directions
            WHERE organization_id = :organizationId
            ORDER BY occurrences DESC, last_seen DESC, normalized_query
            LIMIT :limit
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcUnrecognizedDirectionJournal(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(UnrecognizedDirection observation) {
        jdbc.update(
                UPSERT,
                new MapSqlParameterSource()
                        .addValue("organizationId", observation.organizationId())
                        .addValue("normalizedQuery", observation.normalizedQuery())
                        .addValue("rawQuery", observation.rawQuery())
                        .addValue("suggestions", observation.suggestions().toArray(String[]::new))
                        .addValue("seenAt", OffsetDateTime.ofInstant(observation.lastSeen(), ZoneOffset.UTC)));
    }

    @Override
    public List<UnrecognizedDirection> mostAsked(UUID organizationId, int limit) {
        return jdbc.query(
                MOST_ASKED,
                new MapSqlParameterSource()
                        .addValue("organizationId", organizationId)
                        .addValue("limit", Math.max(limit, 1)),
                MAPPER);
    }

    private static final RowMapper<UnrecognizedDirection> MAPPER = (rs, rowNumber) -> new UnrecognizedDirection(
            rs.getObject("organization_id", UUID.class),
            rs.getString("normalized_query"),
            rs.getString("raw_query"),
            rs.getInt("occurrences"),
            readArray(rs, "suggestions"),
            rs.getObject("first_seen", OffsetDateTime.class).toInstant(),
            rs.getObject("last_seen", OffsetDateTime.class).toInstant());

    private static List<String> readArray(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        if (array == null) {
            return List.of();
        }
        try {
            return List.of((String[]) array.getArray());
        } finally {
            array.free();
        }
    }
}
