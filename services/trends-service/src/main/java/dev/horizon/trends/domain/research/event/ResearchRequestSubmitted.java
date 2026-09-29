package dev.horizon.trends.domain.research.event;

import java.time.Instant;
import java.util.UUID;

import dev.horizon.trends.domain.research.AnalysisParameters;
import dev.horizon.trends.domain.research.RequesterRef;
import dev.horizon.trends.domain.research.ResearchRequestId;
import dev.horizon.trends.domain.research.TechnologyDomainQuery;
import dev.horizon.trends.domain.shared.TrendsDomainEvent;

/** A user asked for an analysis. Starts the saga. */
public record ResearchRequestSubmitted(
        UUID eventId,
        Instant occurredAt,
        ResearchRequestId requestId,
        RequesterRef requester,
        TechnologyDomainQuery query,
        AnalysisParameters parameters,
        int attempt)
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
        return "horizon.trends.ResearchRequestSubmitted";
    }

    @Override
    public String partitionKey() {
        return requestId.toString();
    }

    @Override
    public Object payload() {
        return new Payload(
                requestId.toString(),
                requester.userId().toString(),
                requester.organizationId().toString(),
                query.raw(),
                query.normalized(),
                parameters.topN(),
                parameters.yearsWindow(),
                attempt,
                occurredAt);
    }

    /** Wire shape — decoupled from the internal model so the domain can evolve independently. */
    public record Payload(
            String researchRequestId,
            String userId,
            String organizationId,
            String query,
            String normalizedQuery,
            int topN,
            int yearsWindow,
            int attempt,
            Instant submittedAt) {}
}
