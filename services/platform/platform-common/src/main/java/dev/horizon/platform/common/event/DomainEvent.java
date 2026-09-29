package dev.horizon.platform.common.event;

import java.time.Instant;
import java.util.UUID;

/**
 * A fact that happened in the domain.
 *
 * <p>Implementations are immutable records living in each service's domain layer. The routing
 * metadata ({@link #topic()}, {@link #partitionKey()}) is part of the event because the domain owns
 * the decision of what is published and under which ordering key — the infrastructure only
 * transports it (Dependency Inversion: the outbox adapter depends on this abstraction, not the
 * other way round).
 *
 * <p>{@link #partitionKey()} determines ordering: all saga events for one research request share
 * the request id, so they are strictly ordered within a partition.
 */
public interface DomainEvent {

    UUID eventId();

    Instant occurredAt();

    /** Aggregate type this event originates from, e.g. {@code ResearchRequest}. */
    String aggregateType();

    /** Identifier of the originating aggregate instance. */
    String aggregateId();

    /** Fully qualified event name used for routing and consumer dispatch. */
    String eventType();

    /** Destination topic, e.g. {@code horizon.trends.events.v1}. */
    String topic();

    /** Kafka partition key; determines ordering guarantees. */
    String partitionKey();

    /**
     * The wire payload placed inside the envelope.
     *
     * <p>Defaults to the event itself, but aggregates whose internal representation differs from the
     * published contract override this to return an explicit payload record that mirrors the JSON
     * Schema in {@code contracts/schemas/}. Keeping the two separable is what allows the internal
     * model to evolve without breaking consumers.
     */
    default Object payload() {
        return this;
    }

    /** Schema version of the payload; incremented only for backwards-compatible additions. */
    default int schemaVersion() {
        return 1;
    }
}
