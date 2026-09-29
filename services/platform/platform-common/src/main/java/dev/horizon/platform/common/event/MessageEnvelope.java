package dev.horizon.platform.common.event;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Transport envelope for every message on the bus (contracts/schemas/envelope.json).
 *
 * <p>{@code messageId} is the consumer's idempotency key: at-least-once delivery means duplicates
 * are normal, and consumers deduplicate on this value (ADR-0003). {@code correlationId} ties all
 * messages of one saga together; {@code traceparent} carries W3C trace context across the async
 * boundary so a single trace spans HTTP → Kafka → Python.
 *
 * @param <T> payload type
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MessageEnvelope<T>(
        UUID messageId,
        String type,
        int schemaVersion,
        Instant occurredAt,
        String source,
        String correlationId,
        String causationId,
        String traceparent,
        T payload) {

    public static final String HEADER_MESSAGE_ID = "horizon-message-id";
    public static final String HEADER_MESSAGE_TYPE = "horizon-message-type";
    public static final String HEADER_SCHEMA_VERSION = "horizon-schema-version";
    public static final String HEADER_CORRELATION_ID = "horizon-correlation-id";
    public static final String HEADER_SOURCE = "horizon-source";
    public static final String HEADER_TRACEPARENT = "traceparent";
}
