package dev.horizon.trends.adapter.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Row of {@code trends.trend_feedback}. One verdict per (user, report, trend) — see the unique index. */
@Entity
@Table(name = "trend_feedback")
public class TrendFeedbackEntity {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "report_id", nullable = false)
    private UUID reportId;

    @Column(name = "trend_key", nullable = false, length = 160)
    private String trendKey;

    @Column(name = "verdict", nullable = false, length = 16)
    private String verdict;

    @Column(name = "comment", columnDefinition = "text")
    private String comment;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected TrendFeedbackEntity() {
        // for JPA
    }

    public TrendFeedbackEntity(
            UUID id, UUID userId, UUID reportId, String trendKey, String verdict, String comment, Instant createdAt) {
        this.id = id;
        this.userId = userId;
        this.reportId = reportId;
        this.trendKey = trendKey;
        this.verdict = verdict;
        this.comment = comment;
        this.createdAt = createdAt;
    }

    /** Re-rating replaces the previous verdict; the row's identity is the (user, report, trend) triple. */
    public void revise(String newVerdict, String newComment, Instant at) {
        this.verdict = newVerdict;
        this.comment = newComment;
        this.createdAt = at;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public UUID getReportId() {
        return reportId;
    }

    public String getTrendKey() {
        return trendKey;
    }

    public String getVerdict() {
        return verdict;
    }

    public String getComment() {
        return comment;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
