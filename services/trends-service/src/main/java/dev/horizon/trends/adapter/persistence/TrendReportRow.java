package dev.horizon.trends.adapter.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;

/**
 * Проекция {@code trend_reports} для чтения — только то, чем подписывается вхождение темы.
 *
 * <p>Причины те же, что у {@link ReportTrendRow}: писать отчёт этой проекцией нельзя и не нужно, а
 * читать через неё можно так, чтобы запрос исполнялся тестом.
 */
@Entity
@Immutable
@Table(name = "trend_reports")
public class TrendReportRow {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "research_request_id", nullable = false)
    private UUID researchRequestId;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "raw_query", nullable = false, length = 200)
    private String rawQuery;

    @Column(name = "normalized_query", nullable = false, length = 200)
    private String normalizedQuery;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    protected TrendReportRow() {}

    public UUID getId() {
        return id;
    }

    public int getVersion() {
        return version;
    }

    public String getRawQuery() {
        return rawQuery;
    }

    public String getNormalizedQuery() {
        return normalizedQuery;
    }

    public Instant getGeneratedAt() {
        return generatedAt;
    }
}
