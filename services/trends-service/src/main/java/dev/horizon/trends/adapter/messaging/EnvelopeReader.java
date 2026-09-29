package dev.horizon.trends.adapter.messaging;

import java.util.Optional;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizon.platform.common.event.MessageEnvelope;

/**
 * Unwraps the shared {@link MessageEnvelope} without committing to a payload type.
 *
 * <p>Deserialising straight into a typed envelope would force a listener to know the payload class
 * before it has read {@code type} — impossible on a topic that carries several message kinds. So the
 * envelope is parsed as a tree first, the type is dispatched on, and only then is the payload bound
 * to a record. That order is also what makes "unknown type" a recoverable, acknowledgeable outcome
 * rather than a deserialisation exception that would be retried forever.
 */
@Component
public class EnvelopeReader {

    private final ObjectMapper objectMapper;

    public EnvelopeReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * A parsed envelope with its payload still in tree form.
     *
     * @param messageId consumer-side idempotency key (ADR-0003)
     */
    public record RawMessage(String messageId, String type, JsonNode payload) {}

    /** @return empty when the record is not a Horizon envelope at all */
    public Optional<RawMessage> read(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(value);
            JsonNode payload = root.path("payload");
            String type = root.path("type").asText(null);
            String messageId = root.path("messageId").asText(null);
            if (type == null || messageId == null || payload.isMissingNode()) {
                return Optional.empty();
            }
            return Optional.of(new RawMessage(messageId, type, payload));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Binds the payload tree to a DTO. */
    public <T> T payload(JsonNode payload, Class<T> type) {
        return objectMapper.convertValue(payload, type);
    }
}
