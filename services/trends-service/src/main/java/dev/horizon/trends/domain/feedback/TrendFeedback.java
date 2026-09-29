package dev.horizon.trends.domain.feedback;

import java.time.Instant;
import java.util.UUID;

import dev.horizon.platform.common.id.Uuid7;
import dev.horizon.platform.common.util.Guards;
import dev.horizon.trends.domain.report.TrendReportId;
import dev.horizon.trends.domain.shared.TrendsDomainEvent;

/**
 * An analyst's verdict on a trend (BR-E5, JTBD-7).
 *
 * <p>More than a UI affordance: this is the labelled data that lets ranking be evaluated
 * (Precision@15) and, later, learned. It is therefore published as a domain fact.
 */
public record TrendFeedback(
        UUID id,
        UUID userId,
        TrendReportId reportId,
        String trendKey,
        Verdict verdict,
        String comment,
        Instant createdAt)
        implements TrendsDomainEvent {

    public TrendFeedback {
        Guards.requireNonNull(id, "feedback.id");
        Guards.requireNonNull(userId, "feedback.userId");
        Guards.requireNonNull(reportId, "feedback.reportId");
        Guards.requireLength(trendKey, "feedback.trendKey", 1, 160);
        Guards.requireNonNull(verdict, "feedback.verdict");
        Guards.requireNonNull(createdAt, "feedback.createdAt");
        if (comment != null && comment.length() > 2000) {
            comment = comment.substring(0, 2000);
        }
    }

    public static TrendFeedback record(
            UUID userId, TrendReportId reportId, String trendKey, Verdict verdict, String comment, Instant now) {
        return new TrendFeedback(Uuid7.randomUuid7(), userId, reportId, trendKey, verdict, comment, now);
    }

    public enum Verdict {
        RELEVANT,
        NOISE,
        ALREADY_KNOWN
    }

    @Override
    public UUID eventId() {
        return id;
    }

    @Override
    public Instant occurredAt() {
        return createdAt;
    }

    @Override
    public String aggregateType() {
        return "TrendFeedback";
    }

    @Override
    public String aggregateId() {
        return id.toString();
    }

    @Override
    public String eventType() {
        return "horizon.trends.TrendFeedbackRecorded";
    }

    @Override
    public String partitionKey() {
        return reportId.toString();
    }

    @Override
    public Object payload() {
        return new Payload(reportId.toString(), trendKey, userId.toString(), verdict.name(), comment, createdAt);
    }

    public record Payload(
            String reportId, String trendKey, String userId, String verdict, String comment, Instant recordedAt) {}
}
