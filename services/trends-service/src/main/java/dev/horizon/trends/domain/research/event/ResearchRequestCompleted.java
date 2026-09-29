package dev.horizon.trends.domain.research.event;

import java.time.Instant;
import java.util.UUID;

import dev.horizon.trends.domain.report.TrendReportId;
import dev.horizon.trends.domain.research.RequesterRef;
import dev.horizon.trends.domain.research.ResearchRequestId;
import dev.horizon.trends.domain.shared.TrendsDomainEvent;

/** The analysis finished and a report exists. */
public record ResearchRequestCompleted(
        UUID eventId,
        Instant occurredAt,
        ResearchRequestId requestId,
        TrendReportId reportId,
        RequesterRef requester,
        String normalizedQuery,
        int trendCount,
        boolean partial,
        long durationMs)
        implements TrendsDomainEvent {

    @Override
    public String aggregateType() {
        return "ResearchRequest";
    }

    @Override
    public String aggregateId() {
        return requestId.toString();
    }

    @Override
    public String eventType() {
        return "horizon.trends.ResearchRequestCompleted";
    }

    @Override
    public String partitionKey() {
        return requestId.toString();
    }

    @Override
    public Object payload() {
        return new Payload(
                requestId.toString(),
                reportId.toString(),
                requester.userId().toString(),
                normalizedQuery,
                trendCount,
                partial,
                durationMs,
                occurredAt);
    }

    public record Payload(
            String researchRequestId,
            String reportId,
            String userId,
            String normalizedQuery,
            int trendCount,
            boolean partial,
            long durationMs,
            Instant completedAt) {}
}
