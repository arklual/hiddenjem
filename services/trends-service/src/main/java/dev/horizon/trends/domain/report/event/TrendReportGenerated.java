package dev.horizon.trends.domain.report.event;

import java.time.Instant;
import java.util.UUID;

import dev.horizon.trends.domain.report.TrendReportId;
import dev.horizon.trends.domain.research.RequesterRef;
import dev.horizon.trends.domain.research.ResearchRequestId;
import dev.horizon.trends.domain.shared.TrendsDomainEvent;

/** A new immutable report exists. Matches {@code contracts/schemas/trend-report-generated.event.json}. */
public record TrendReportGenerated(
        UUID eventId,
        Instant occurredAt,
        TrendReportId reportId,
        ResearchRequestId researchRequestId,
        RequesterRef requester,
        String normalizedQuery,
        int trendCount,
        boolean partial,
        String methodologyVersion,
        UUID corpusSnapshotId)
        implements TrendsDomainEvent {

    @Override
    public String aggregateType() {
        return "TrendReport";
    }

    @Override
    public String aggregateId() {
        return reportId.toString();
    }

    @Override
    public String eventType() {
        return "horizon.trends.TrendReportGenerated";
    }

    @Override
    public String partitionKey() {
        return researchRequestId.toString();
    }

    @Override
    public Object payload() {
        return new Payload(
                reportId.toString(),
                researchRequestId.toString(),
                requester.userId().toString(),
                requester.organizationId().toString(),
                normalizedQuery,
                trendCount,
                partial,
                methodologyVersion,
                corpusSnapshotId.toString(),
                null,
                occurredAt);
    }

    public record Payload(
            String reportId,
            String researchRequestId,
            String userId,
            String organizationId,
            String normalizedQuery,
            int trendCount,
            boolean partial,
            String methodologyVersion,
            String corpusSnapshotId,
            Long durationMs,
            Instant generatedAt) {}
}
