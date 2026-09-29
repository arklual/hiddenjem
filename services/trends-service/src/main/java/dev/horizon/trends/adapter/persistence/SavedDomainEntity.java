package dev.horizon.trends.adapter.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Row of {@code trends.saved_domains}.
 *
 * <p>The analysis parameters live in one {@code jsonb} column instead of six typed ones: nothing
 * queries a saved domain <em>by</em> its parameters, they are always read whole together with the
 * row, and adding a parameter must not require a migration of a purely convenience-level table.
 */
@Entity
@Table(name = "saved_domains")
public class SavedDomainEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "raw_query", nullable = false, length = 200)
    private String rawQuery;

    @Column(name = "normalized_query", nullable = false, length = 200)
    private String normalizedQuery;

    @Column(name = "query_language", length = 2)
    private String queryLanguage;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "parameters", nullable = false)
    private String parameters;

    @Column(name = "last_report_id")
    private UUID lastReportId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SavedDomainEntity() {
        // for JPA
    }

    public SavedDomainEntity(
            UUID id,
            UUID userId,
            String rawQuery,
            String normalizedQuery,
            String queryLanguage,
            String parameters,
            UUID lastReportId,
            Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.rawQuery = rawQuery;
        this.normalizedQuery = normalizedQuery;
        this.queryLanguage = queryLanguage;
        this.parameters = parameters;
        this.lastReportId = lastReportId;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getRawQuery() {
        return rawQuery;
    }

    public String getNormalizedQuery() {
        return normalizedQuery;
    }

    public String getQueryLanguage() {
        return queryLanguage;
    }

    public String getParameters() {
        return parameters;
    }

    public UUID getLastReportId() {
        return lastReportId;
    }

    public void setLastReportId(UUID lastReportId) {
        this.lastReportId = lastReportId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
