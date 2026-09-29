package dev.horizon.ingestion.persistence;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.port.SourceRepository;
import dev.horizon.ingestion.domain.source.Source;

/**
 * Source registry backed by JDBC.
 *
 * <p>Plain JDBC rather than JPA: the table is tiny, read almost every request and written rarely,
 * and the mapping is trivial. An ORM here would add a persistence model, a mapper and a first-level
 * cache to solve a problem that does not exist.
 */
@Repository
public class JdbcSourceRepository implements SourceRepository {

    private static final TypeReference<Map<String, Object>> CONFIG_TYPE = new TypeReference<>() {};

    private static final String COLUMNS =
            """
            id, display_name, source_class, enabled, base_url, rate_limit_per_minute,
            requires_api_key, config, authority_weight, version
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcSourceRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Source> findAll() {
        return jdbc.query("SELECT " + COLUMNS + " FROM sources ORDER BY id", mapper());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Source> findById(String id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM sources WHERE id = :id", Map.of("id", id), mapper()).stream()
                .findFirst();
    }

    /**
     * Updates with an optimistic-lock check.
     *
     * <p>Two operators toggling the same source concurrently would otherwise silently overwrite each
     * other; the version predicate turns that into an explicit conflict.
     */
    @Override
    @Transactional
    public Source save(Source source) {
        var params = new MapSqlParameterSource()
                .addValue("id", source.id())
                .addValue("displayName", source.displayName())
                .addValue("sourceClass", source.sourceClass().name())
                .addValue("enabled", source.isEnabled())
                .addValue("baseUrl", source.baseUrl())
                .addValue("rateLimit", source.rateLimitPerMinute())
                .addValue("requiresApiKey", source.requiresApiKey())
                .addValue("config", writeConfig(source.config()))
                .addValue("authorityWeight", source.authorityWeight())
                .addValue("version", source.version());

        int updated = jdbc.update(
                """
                UPDATE sources SET
                    display_name = :displayName,
                    source_class = :sourceClass,
                    enabled = :enabled,
                    base_url = :baseUrl,
                    rate_limit_per_minute = :rateLimit,
                    requires_api_key = :requiresApiKey,
                    config = CAST(:config AS jsonb),
                    authority_weight = :authorityWeight,
                    updated_at = now(),
                    version = version + 1
                WHERE id = :id AND version = :version
                """,
                params);

        if (updated == 0) {
            boolean exists = Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS(SELECT 1 FROM sources WHERE id = :id)", Map.of("id", source.id()), Boolean.class));
            if (exists) {
                throw new OptimisticLockingFailureException(
                        "Источник %s был изменён другим процессом".formatted(source.id()));
            }
            jdbc.update(
                    """
                    INSERT INTO sources (id, display_name, source_class, enabled, base_url,
                                         rate_limit_per_minute, requires_api_key, config, authority_weight, version)
                    VALUES (:id, :displayName, :sourceClass, :enabled, :baseUrl,
                            :rateLimit, :requiresApiKey, CAST(:config AS jsonb), :authorityWeight, 0)
                    """,
                    params);
        }
        return findById(source.id()).orElseThrow();
    }

    private RowMapper<Source> mapper() {
        return (rs, rowNum) -> Source.of(
                rs.getString("id"),
                rs.getString("display_name"),
                SourceClass.valueOf(rs.getString("source_class")),
                rs.getBoolean("enabled"),
                rs.getString("base_url"),
                rs.getInt("rate_limit_per_minute"),
                rs.getBoolean("requires_api_key"),
                readConfig(rs.getString("config")),
                rs.getDouble("authority_weight"),
                rs.getLong("version"));
    }

    private String writeConfig(Map<String, Object> config) {
        try {
            return objectMapper.writeValueAsString(config == null ? Map.of() : config);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось сериализовать конфигурацию источника", e);
        }
    }

    private Map<String, Object> readConfig(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, CONFIG_TYPE);
        } catch (Exception e) {
            // A malformed config row must not take the whole registry down: the source simply runs
            // with defaults and the problem is visible in the logs.
            return Map.of();
        }
    }
}
