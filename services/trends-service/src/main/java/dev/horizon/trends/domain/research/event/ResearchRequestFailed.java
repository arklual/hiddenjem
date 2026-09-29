package dev.horizon.trends.domain.research.event;

import java.time.Instant;
import java.util.UUID;

import dev.horizon.trends.domain.research.FailureInfo;
import dev.horizon.trends.domain.research.ResearchRequestId;
import dev.horizon.trends.domain.shared.TrendsDomainEvent;

/** The analysis could not be produced. Matches {@code contracts/schemas/failure.event.json}. */
public record ResearchRequestFailed(
        UUID eventId, Instant occurredAt, ResearchRequestId requestId, int attempt, FailureInfo failure)
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
        return "horizon.trends.ResearchRequestFailed";
    }

    @Override
    public String partitionKey() {
        return requestId.toString();
    }

    @Override
    public Object payload() {
        return new Payload(
                requestId.toString(),
                attempt,
                failure.code(),
                failure.message(),
                failure.retryable(),
                java.util.Map.of());
    }

    public record Payload(
            String researchRequestId,
            int attempt,
            String code,
            String message,
            boolean retryable,
            java.util.Map<String, Object> details) {}
}
