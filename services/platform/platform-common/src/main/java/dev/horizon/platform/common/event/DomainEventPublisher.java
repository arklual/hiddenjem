package dev.horizon.platform.common.event;

import java.util.Collection;
import java.util.List;

/**
 * Outbound port for publishing domain events (Dependency Inversion).
 *
 * <p>The domain and application layers depend on this interface only. The production adapter writes
 * to the transactional outbox inside the caller's transaction (ADR-0003), so "save the aggregate and
 * publish the event" is atomic without two-phase commit. Tests substitute an in-memory recorder.
 *
 * <p>Contract: implementations MUST participate in the ambient transaction and MUST NOT perform
 * network I/O — delivery happens asynchronously after commit.
 */
public interface DomainEventPublisher {

    void publish(Collection<? extends DomainEvent> events);

    default void publish(DomainEvent event) {
        publish(List.of(event));
    }
}
