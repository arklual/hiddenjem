package dev.horizon.ingestion.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizon.ingestion.application.SnapshotDocumentsQuery;

/**
 * Собирает документы снапшота в канонический вид {@code document-ingested}.
 *
 * <p>Три запроса на страницу, а не N+1: сами документы, их авторы и их предметные коды. Коды здесь
 * существеннее, чем кажется — на них держится отнесение темы к направлению (методология §12), и
 * страница без них превратила бы отбор в запасной путь «лучшие N по сходству».
 *
 * <p>Порядок строк задаёт {@code corpus_snapshot_documents.ordinal}: он же определяет содержимое
 * снапшота и его хэш, то есть воспроизводимость анализа.
 */
@Repository
public class JdbcSnapshotDocumentsQuery implements SnapshotDocumentsQuery {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcSnapshotDocumentsQuery(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Map<String, Object>> page(UUID snapshotId, int offset, int limit) {
        var parameters = new MapSqlParameterSource()
                .addValue("id", snapshotId)
                .addValue("limit", limit)
                .addValue("offset", offset);

        List<Row> rows = jdbc.query(
                """
                SELECT d.*, s.ordinal
                FROM corpus_snapshot_documents s
                JOIN documents d ON d.id = s.document_id
                WHERE s.snapshot_id = :id
                ORDER BY s.ordinal
                LIMIT :limit OFFSET :offset
                """,
                parameters,
                (rs, rowNum) -> new Row(rs.getObject("id", UUID.class), document(rs)));
        if (rows.isEmpty()) {
            return List.of();
        }

        var ids = rows.stream().map(Row::id).toList();
        var byDocument = new MapSqlParameterSource("ids", ids);

        Map<UUID, List<Map<String, Object>>> authors = new LinkedHashMap<>();
        jdbc.query(
                """
                SELECT document_id, full_name, orcid, organization_name, organization_type, organization_country
                FROM document_authors
                WHERE document_id IN (:ids)
                ORDER BY document_id, ordinal
                """,
                byDocument,
                rs -> {
                    var author = new LinkedHashMap<String, Object>();
                    author.put("fullName", rs.getString("full_name"));
                    author.put("orcid", rs.getString("orcid"));
                    author.put("organizationName", rs.getString("organization_name"));
                    author.put("organizationType", rs.getString("organization_type"));
                    author.put("organizationCountry", rs.getString("organization_country"));
                    authors.computeIfAbsent(rs.getObject("document_id", UUID.class), key -> new ArrayList<>())
                            .add(author);
                });

        Map<UUID, List<Map<String, Object>>> topics = new LinkedHashMap<>();
        jdbc.query(
                """
                SELECT document_id, code, label, score
                FROM document_topics_raw
                WHERE document_id IN (:ids)
                ORDER BY document_id, code
                """,
                byDocument,
                rs -> {
                    var topic = new LinkedHashMap<String, Object>();
                    topic.put("code", rs.getString("code"));
                    topic.put("label", rs.getString("label"));
                    var score = rs.getBigDecimal("score");
                    topic.put("score", score == null ? null : score.doubleValue());
                    topics.computeIfAbsent(rs.getObject("document_id", UUID.class), key -> new ArrayList<>())
                            .add(topic);
                });

        var page = new ArrayList<Map<String, Object>>(rows.size());
        for (Row row : rows) {
            var document = row.document();
            document.put("authors", authors.getOrDefault(row.id(), List.of()));
            document.put("topics", topics.getOrDefault(row.id(), List.of()));
            page.add(document);
        }
        return page;
    }

    private Map<String, Object> document(ResultSet rs) throws SQLException {
        var document = new LinkedHashMap<String, Object>();
        document.put("documentId", rs.getObject("id", UUID.class).toString());
        document.put("sourceId", rs.getString("source_id"));
        document.put("sourceClass", rs.getString("source_class"));
        document.put("externalId", rs.getString("external_id"));
        // Переведённый документ уходит в анализ английским текстом: извлечение терминов работает на
        // одном языке, и «федеративное обучение» с «federated learning» должны сложиться в одну тему.
        // Оригинал и модель перевода идут рядом — для свидетельств и раскрытия (ТЗ §3.1).
        String translatedTitle = rs.getString("title_en");
        if (translatedTitle != null && !translatedTitle.isBlank()) {
            document.put("title", translatedTitle);
            document.put("abstractText", rs.getString("abstract_en"));
            document.put("originalTitle", rs.getString("title"));
            document.put("translatedFrom", rs.getString("translated_from"));
            document.put("translationModel", rs.getString("translation_model"));
            String language = rs.getString("language");
            document.put("language", language == null ? rs.getString("translated_from") : language);
        } else {
            document.put("title", rs.getString("title"));
            document.put("abstractText", rs.getString("abstract_text"));
            document.put("language", rs.getString("language"));
        }
        document.put("publishedOn", rs.getDate("published_on").toLocalDate().toString());
        document.put("doi", rs.getString("doi"));
        document.put("arxivId", rs.getString("arxiv_id"));
        document.put("patentNumber", rs.getString("patent_number"));
        document.put("url", rs.getString("url"));
        var venueName = rs.getString("venue_name");
        if (venueName != null) {
            var venue = new LinkedHashMap<String, Object>();
            venue.put("name", venueName);
            venue.put("type", rs.getString("venue_type"));
            venue.put("issn", rs.getString("venue_issn"));
            document.put("venue", venue);
        }
        var citations = rs.getObject("citation_count");
        document.put("citationCount", citations == null ? null : ((Number) citations).intValue());
        document.put("dedupKey", rs.getString("dedup_key"));
        document.put("fetchedAt", rs.getObject("fetched_at", java.time.OffsetDateTime.class).toString());
        // Метрики источника (звёзды репозитория, цитирования патента) приходят словарём и нужны
        // индикаторам как есть: разбирать их здесь значило бы завести второй словарь имён.
        var extra = rs.getString("extra_metrics");
        if (extra != null && !extra.isBlank() && !"{}".equals(extra)) {
            try {
                document.put("extraMetrics", objectMapper.readValue(extra, Map.class));
            } catch (Exception e) {
                document.put("extraMetrics", Map.of());
            }
        }
        return document;
    }

    private record Row(UUID id, Map<String, Object> document) {}
}
