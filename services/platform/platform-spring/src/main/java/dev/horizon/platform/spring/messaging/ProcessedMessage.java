package dev.horizon.platform.spring.messaging;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/**
 * Record of a message already handled by a given consumer — the deduplication key for
 * at-least-once delivery (ADR-0003).
 *
 * <p>Scoped by consumer name, not global: two different consumer groups must each be allowed to
 * process the same message exactly once.
 */
@Entity
@Table(name = "processed_messages")
@IdClass(ProcessedMessage.Key.class)
public class ProcessedMessage {

    @Id
    @Column(name = "consumer", nullable = false, length = 64)
    private String consumer;

    @Id
    @Column(name = "message_id", nullable = false, length = 64)
    private String messageId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    protected ProcessedMessage() {}

    public ProcessedMessage(String consumer, String messageId, Instant processedAt) {
        this.consumer = consumer;
        this.messageId = messageId;
        this.processedAt = processedAt;
    }

    public String getConsumer() {
        return consumer;
    }

    public String getMessageId() {
        return messageId;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    /** Composite primary key. */
    public static class Key implements Serializable {
        private String consumer;
        private String messageId;

        public Key() {}

        public Key(String consumer, String messageId) {
            this.consumer = consumer;
            this.messageId = messageId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Key key)) {
                return false;
            }
            return Objects.equals(consumer, key.consumer) && Objects.equals(messageId, key.messageId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(consumer, messageId);
        }
    }
}
