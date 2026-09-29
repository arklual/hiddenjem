package dev.horizon.trends.adapter.messaging;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizon.platform.common.event.MessageEnvelope;
import dev.horizon.platform.spring.messaging.OutboxDomainEventPublisher;
import dev.horizon.platform.spring.messaging.OutboxMessage;
import dev.horizon.platform.spring.messaging.OutboxMessageRepository;
import dev.horizon.platform.spring.web.TraceIds;
import dev.horizon.trends.application.port.CommandSender;

/**
 * Writes outbound commands into the transactional outbox (ADR-0003).
 *
 * <p>This is the command-side twin of {@link OutboxDomainEventPublisher}. It is not built on top of
 * that class because the platform publisher is typed to {@code DomainEvent} — a fact, with an
 * aggregate type, an aggregate id and a payload derived from it — while a command is an instruction
 * addressed to another service, carries no aggregate of its own, and is routed to a topic the sender
 * chooses. Forcing a command through a {@code DomainEvent} adapter would mean inventing a fake
 * aggregate and a fake event type, which is exactly the kind of lie that later reads as a bug.
 *
 * <p>What <em>is</em> shared is the wire contract, and that is shared by reference rather than by
 * copy: the envelope is {@link MessageEnvelope}, the headers use its {@code HEADER_*} constants, and
 * the row is a {@link OutboxMessage} written through the platform repository. A change to the
 * envelope shape therefore lands here automatically.
 *
 * <p>{@link Propagation#MANDATORY} for the same reason as the platform publisher: sending a command
 * outside the transaction that produced the state change would break the atomicity the outbox
 * exists to provide, and failing loudly in development beats losing a saga step in production.
 */
@Component
public class OutboxCommandSender implements CommandSender {

    /**
     * Recorded in {@code outbox_messages.aggregate_type}. Every command this service sends is issued
     * on behalf of one research request, which is also the partition key — that is what keeps a
     * saga's messages strictly ordered.
     */
    private static final String AGGREGATE_TYPE = "ResearchRequest";

    private final OutboxMessageRepository outbox;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String serviceName;

    public OutboxCommandSender(
            OutboxMessageRepository outbox,
            ObjectMapper objectMapper,
            Clock clock,
            org.springframework.core.env.Environment environment) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.serviceName = environment.getProperty("spring.application.name", "trends-service");
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void send(OutboundCommand command) {
        var now = clock.instant();
        var envelope = new MessageEnvelope<>(
                command.messageId(),
                command.type(),
                1,
                now,
                serviceName,
                command.partitionKey(),
                command.causationId(),
                TraceIds.currentTraceparent(),
                command.payload());

        outbox.save(new OutboxMessage(
                command.messageId(),
                AGGREGATE_TYPE,
                command.partitionKey(),
                command.type(),
                command.topic(),
                command.partitionKey(),
                serialize(envelope),
                serialize(headers(command)),
                now));
    }

    /** Header set mirrors {@link OutboxDomainEventPublisher} so consumers can filter identically. */
    private Map<String, String> headers(OutboundCommand command) {
        var headers = new LinkedHashMap<String, String>();
        headers.put(MessageEnvelope.HEADER_MESSAGE_ID, command.messageId().toString());
        headers.put(MessageEnvelope.HEADER_MESSAGE_TYPE, command.type());
        headers.put(MessageEnvelope.HEADER_SCHEMA_VERSION, "1");
        headers.put(MessageEnvelope.HEADER_CORRELATION_ID, command.partitionKey());
        headers.put(MessageEnvelope.HEADER_SOURCE, serviceName);
        String traceparent = TraceIds.currentTraceparent();
        if (traceparent != null) {
            headers.put(MessageEnvelope.HEADER_TRACEPARENT, traceparent);
        }
        // Caller-supplied headers last so a command can add context without shadowing the contract.
        if (command.headers() != null) {
            command.headers().forEach(headers::putIfAbsent);
        }
        return headers;
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Не удалось сериализовать команду для outbox: " + value.getClass(), e);
        }
    }
}
