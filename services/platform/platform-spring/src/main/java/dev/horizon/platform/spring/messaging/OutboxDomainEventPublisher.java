package dev.horizon.platform.spring.messaging;

import java.time.Clock;
import java.util.Collection;
import java.util.Map;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizon.platform.common.event.DomainEvent;
import dev.horizon.platform.common.event.DomainEventPublisher;
import dev.horizon.platform.common.event.MessageEnvelope;
import dev.horizon.platform.spring.web.TraceIds;

/**
 * Persists domain events into the outbox table inside the caller's transaction.
 *
 * <p>{@link Propagation#MANDATORY} is intentional and load-bearing: publishing outside a transaction
 * would silently break the atomicity guarantee the whole design rests on. Failing loudly at
 * development time is far better than discovering lost events in production.
 */
public class OutboxDomainEventPublisher implements DomainEventPublisher {

    private final OutboxMessageRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String serviceName;

    public OutboxDomainEventPublisher(
            OutboxMessageRepository repository, ObjectMapper objectMapper, Clock clock, String serviceName) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.serviceName = serviceName;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publish(Collection<? extends DomainEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }
        var now = clock.instant();
        for (DomainEvent event : events) {
            var envelope = new MessageEnvelope<>(
                    event.eventId(),
                    event.eventType(),
                    event.schemaVersion(),
                    event.occurredAt(),
                    serviceName,
                    event.partitionKey(),
                    null,
                    TraceIds.currentTraceparent(),
                    event.payload());
            repository.save(new OutboxMessage(
                    event.eventId(),
                    event.aggregateType(),
                    event.aggregateId(),
                    event.eventType(),
                    event.topic(),
                    event.partitionKey(),
                    serialize(envelope),
                    serialize(headers(event)),
                    now));
        }
    }

    private Map<String, String> headers(DomainEvent event) {
        var traceparent = TraceIds.currentTraceparent();
        var headers = new java.util.LinkedHashMap<String, String>();
        headers.put(MessageEnvelope.HEADER_MESSAGE_ID, event.eventId().toString());
        headers.put(MessageEnvelope.HEADER_MESSAGE_TYPE, event.eventType());
        headers.put(MessageEnvelope.HEADER_SCHEMA_VERSION, String.valueOf(event.schemaVersion()));
        headers.put(MessageEnvelope.HEADER_CORRELATION_ID, event.partitionKey());
        headers.put(MessageEnvelope.HEADER_SOURCE, serviceName);
        if (traceparent != null) {
            headers.put(MessageEnvelope.HEADER_TRACEPARENT, traceparent);
        }
        return headers;
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            // Non-serialisable event is a programming error, not a runtime condition.
            throw new IllegalStateException("Failed to serialise outbox payload: " + value.getClass(), e);
        }
    }
}
