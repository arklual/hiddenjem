package dev.horizon.ingestion.persistence;

import java.sql.Date;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizon.ingestion.domain.document.Author;
import dev.horizon.ingestion.domain.document.DedupKey;
import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.document.DocumentTopic;
import dev.horizon.ingestion.domain.document.ExternalRef;
import dev.horizon.ingestion.domain.port.DocumentRepository;

/**
 * Canonical document storage.
 *
 * <p>Writes are idempotent by construction: the insert is {@code ON CONFLICT DO NOTHING} against the
 * two uniqueness rules that define "the same document" — {@code (source_id, external_id,
 * published_on)} and {@code (dedup_key, published_on)}. At-least-once delivery and re-runs after a
 * crash therefore cost nothing beyond a no-op insert, which is exactly what makes the collection step
 * safely repeatable.
 *
 * <p><b>Дата входит в оба правила, и это стоит читать буквально.</b> Таблица секционирована по
 * {@code published_on}, а PostgreSQL требует, чтобы ключ секционирования входил в любое ограничение
 * уникальности. Дедупликация поэтому работает «в пределах даты публикации», а не по одному лишь
 * содержимому — прежняя формулировка обещала второе.
 *
 * <p>Следствие настоящее: одна работа, пришедшая из двух источников с разными датами (один отдаёт
 * дату онлайн-первой публикации, другой печатной), ляжет двумя строками. BRULE-1 это переживает — он
 * считает организации, а не документы, — а {@code totalDocuments}, {@code weakness} и
 * {@code confidence} считают документы и сдвинутся. Замерить величину эффекта не на чем: эталонный
 * корпус повторов не содержит вовсе, поэтому здесь записана граница, а не оценка.
 *
 * <p>Child rows (authors, topics) are written in JDBC batches; a document with 200 authors is common
 * in physics and would otherwise mean 200 round-trips.
 */
@Repository
public class JdbcDocumentRepository implements DocumentRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcDocumentRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> findIdByExternalRef(ExternalRef ref, LocalDate publishedOn) {
        return queryId(
                """
                SELECT id FROM documents
                WHERE source_id = :sourceId AND external_id = :externalId AND published_on = :publishedOn
                """,
                new MapSqlParameterSource()
                        .addValue("sourceId", ref.sourceId())
                        .addValue("externalId", ref.externalId())
                        .addValue("publishedOn", Date.valueOf(publishedOn)));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> findIdByDedupKey(DedupKey dedupKey, LocalDate publishedOn) {
        return queryId(
                "SELECT id FROM documents WHERE dedup_key = :dedupKey AND published_on = :publishedOn",
                new MapSqlParameterSource()
                        .addValue("dedupKey", dedupKey.value())
                        .addValue("publishedOn", Date.valueOf(publishedOn)));
    }

    @Override
    @Transactional
    public UUID save(Document document) {
        var params = new MapSqlParameterSource()
                .addValue("id", document.id())
                .addValue("sourceId", document.sourceId())
                .addValue("externalId", document.externalId())
                .addValue("sourceClass", document.sourceClass().name())
                .addValue("title", document.title())
                .addValue("abstractText", document.abstractText())
                .addValue("language", document.language())
                .addValue("publishedOn", Date.valueOf(document.publishedOn()))
                .addValue("doi", document.identifiers().doi())
                .addValue("arxivId", document.identifiers().arxivId())
                .addValue("patentNumber", document.identifiers().patentNumber())
                .addValue("url", document.identifiers().url())
                .addValue(
                        "venueName",
                        document.venue() == null ? null : document.venue().name())
                .addValue(
                        "venueType",
                        document.venue() == null ? null : document.venue().type())
                .addValue(
                        "venueIssn",
                        document.venue() == null ? null : document.venue().issn())
                .addValue("citationCount", document.metrics().citationCount(), Types.INTEGER)
                .addValue("extraMetrics", writeJson(document.extraMetrics()))
                .addValue("dedupKey", document.dedupKey().value())
                .addValue("fetchedAt", Timestamp.from(document.provenance().fetchedAt()))
                .addValue("requestUrl", document.provenance().requestUrl())
                .addValue("httpStatus", document.provenance().httpStatus(), Types.SMALLINT)
                .addValue("payloadHash", document.provenance().payloadHash())
                .addValue("rawRef", document.provenance().rawRef());

        jdbc.update(
                """
                INSERT INTO documents (
                    id, source_id, external_id, source_class, title, abstract_text, language, published_on,
                    doi, arxiv_id, patent_number, url, venue_name, venue_type, venue_issn,
                    citation_count, extra_metrics, dedup_key, fetched_at, request_url, http_status,
                    payload_hash, raw_ref, version)
                VALUES (
                    :id, :sourceId, :externalId, :sourceClass, :title, :abstractText, :language, :publishedOn,
                    :doi, :arxivId, :patentNumber, :url, :venueName, :venueType, :venueIssn,
                    :citationCount, CAST(:extraMetrics AS jsonb), :dedupKey, :fetchedAt, :requestUrl, :httpStatus,
                    :payloadHash, :rawRef, 0)
                ON CONFLICT DO NOTHING
                """,
                params);

        // Re-read rather than trusting the generated id: on conflict the row that already exists
        // wins, and callers must be given the identifier that is actually stored.
        UUID storedId = findIdByExternalRef(document.externalRef(), document.publishedOn())
                .or(() -> findIdByDedupKey(document.dedupKey(), document.publishedOn()))
                .orElse(document.id());

        if (storedId.equals(document.id())) {
            saveAuthors(document);
            saveTopics(document);
        }
        return storedId;
    }

    @Override
    @Transactional(readOnly = true)
    public long countBySourceId(String sourceId) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM documents WHERE source_id = :sourceId", Map.of("sourceId", sourceId), Long.class);
        return count == null ? 0L : count;
    }

    private void saveAuthors(Document document) {
        List<Author> authors = document.authors();
        if (authors.isEmpty()) {
            return;
        }
        SqlParameterSource[] batch = new SqlParameterSource[authors.size()];
        for (int i = 0; i < authors.size(); i++) {
            Author author = authors.get(i);
            batch[i] = new MapSqlParameterSource()
                    .addValue("documentId", document.id())
                    .addValue("publishedOn", Date.valueOf(document.publishedOn()))
                    .addValue("ordinal", i)
                    .addValue("fullName", author.fullName())
                    .addValue("orcid", author.orcid())
                    .addValue("organizationName", author.organizationName())
                    .addValue(
                            "organizationType",
                            author.organizationType() == null
                                    ? null
                                    : author.organizationType().name())
                    .addValue("organizationCountry", author.organizationCountry());
        }
        jdbc.batchUpdate(
                """
                INSERT INTO document_authors (document_id, published_on, ordinal, full_name, orcid,
                                              organization_name, organization_type, organization_country)
                VALUES (:documentId, :publishedOn, :ordinal, :fullName, :orcid,
                        :organizationName, :organizationType, :organizationCountry)
                ON CONFLICT DO NOTHING
                """,
                batch);
    }

    private void saveTopics(Document document) {
        List<DocumentTopic> topics = document.topics();
        if (topics.isEmpty()) {
            return;
        }
        SqlParameterSource[] batch = new SqlParameterSource[topics.size()];
        for (int i = 0; i < topics.size(); i++) {
            DocumentTopic topic = topics.get(i);
            batch[i] = new MapSqlParameterSource()
                    .addValue("documentId", document.id())
                    .addValue("publishedOn", Date.valueOf(document.publishedOn()))
                    .addValue("code", topic.code())
                    .addValue("label", topic.label())
                    .addValue("score", topic.score(), Types.NUMERIC);
        }
        jdbc.batchUpdate(
                """
                INSERT INTO document_topics_raw (document_id, published_on, code, label, score)
                VALUES (:documentId, :publishedOn, :code, :label, :score)
                ON CONFLICT DO NOTHING
                """,
                batch);
    }

    private Optional<UUID> queryId(String sql, SqlParameterSource params) {
        return jdbc.query(sql, params, (rs, rowNum) -> rs.getObject("id", UUID.class)).stream()
                .findFirst();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            throw new IllegalStateException("Не удалось сериализовать метрики документа", e);
        }
    }
}
