package dev.horizon.ingestion.persistence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import dev.horizon.ingestion.domain.port.DocumentTexts;

/** Тексты документов из {@code documents}: оригинал и перевод, если он есть. */
@Repository
public class JdbcDocumentTexts implements DocumentTexts {

    private static final int BATCH = 1000;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcDocumentTexts(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<DocumentText> texts(Collection<UUID> ids) {
        List<UUID> all = List.copyOf(ids);
        List<DocumentText> out = new ArrayList<>(all.size());
        for (int from = 0; from < all.size(); from += BATCH) {
            List<UUID> batch = all.subList(from, Math.min(all.size(), from + BATCH));
            out.addAll(jdbc.query(
                    """
                    SELECT id, source_id,
                           concat_ws(' ', title, abstract_text, title_en, abstract_en) AS text
                    FROM documents
                    WHERE id IN (:ids)
                    """,
                    new MapSqlParameterSource("ids", batch),
                    (rs, row) -> new DocumentText(
                            rs.getObject("id", UUID.class), rs.getString("source_id"), rs.getString("text"))));
        }
        return out;
    }
}
