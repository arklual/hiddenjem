package dev.horizon.ingestion.persistence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import dev.horizon.ingestion.domain.port.DocumentTranslations;

@Repository
public class JdbcDocumentTranslations implements DocumentTranslations {

    /** Сколько идентификаторов в одном IN: у PostgreSQL предел параметров запроса — 32 767. */
    private static final int CHUNK = 1000;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcDocumentTranslations(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Pending> untranslated(Collection<UUID> ids) {
        List<UUID> all = new ArrayList<>(ids);
        List<Pending> pending = new ArrayList<>();
        for (int start = 0; start < all.size(); start += CHUNK) {
            var chunk = all.subList(start, Math.min(start + CHUNK, all.size()));
            pending.addAll(jdbc.query(
                    """
                    SELECT id, title, abstract_text,
                           CASE WHEN title ~ '[一-鿿]' THEN 'zh' ELSE 'ru' END AS script
                    FROM documents
                    WHERE id IN (:ids)
                      AND translation_model IS NULL
                      AND title ~ '[А-Яа-яЁё一-鿿]'
                    ORDER BY id
                    """,
                    new MapSqlParameterSource("ids", chunk),
                    (rs, rowNum) -> new Pending(
                            rs.getObject("id", UUID.class),
                            rs.getString("title"),
                            rs.getString("abstract_text"),
                            rs.getString("script"))));
        }
        return pending;
    }

    @Override
    public void save(UUID id, Translated translation, String sourceLanguage, String model) {
        jdbc.update(
                """
                UPDATE documents
                   SET title_en = :title, abstract_en = :abstract, translated_from = :language,
                       translation_model = :model, translated_at = now()
                 WHERE id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("title", translation.title())
                        .addValue("abstract", translation.abstractText())
                        .addValue("language", sourceLanguage)
                        .addValue("model", model == null ? "unknown" : model));
    }
}
