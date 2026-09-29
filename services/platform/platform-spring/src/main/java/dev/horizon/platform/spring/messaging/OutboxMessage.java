package dev.horizon.platform.spring.messaging;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A message awaiting delivery to the broker (ADR-0003).
 *
 * <p>Rows are written inside the same transaction as the aggregate change, which makes
 * "state changed" and "event published" atomic without distributed transactions. A separate poller
 * delivers them at-least-once.
 *
 * <p>The table lives in each service's own schema; the default schema of the datasource is used, so
 * this entity is reusable across services without duplication.
 */
@Entity
@Table(name = "outbox_messages")
public class OutboxMessage {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, length = 64)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, length = 96)
    private String eventType;

    @Column(name = "topic", nullable = false, length = 120)
    private String topic;

    @Column(name = "partition_key", nullable = false, length = 120)
    private String partitionKey;

    @Column(name = "payload", nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "headers", nullable = false, columnDefinition = "text")
    private String headers;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempts", nullable = false)
    private short attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    protected OutboxMessage() {
        // for JPA
    }

    public OutboxMessage(
            UUID id,
            String aggregateType,
            String aggregateId,
            String eventType,
            String topic,
            String partitionKey,
            String payload,
            String headers,
            Instant createdAt) {
        this.id = id;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.topic = topic;
        this.partitionKey = partitionKey;
        this.payload = payload;
        this.headers = headers;
        this.createdAt = createdAt;
        this.attempts = 0;
        this.nextAttemptAt = createdAt;
    }

    public void markPublished(Instant at) {
        this.publishedAt = at;
        this.lastError = null;
    }

    /**
     * Records a delivery failure and schedules the next attempt with exponential backoff capped at
     * {@code maxBackoff}. Capping matters: without it a long outage pushes retries hours into the
     * future and the queue never drains after recovery.
     */
    public void markFailed(Instant now, String error, Duration baseBackoff, Duration maxBackoff) {
        this.attempts = (short) Math.min(this.attempts + 1, Short.MAX_VALUE);
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 2000));
        int shift = Math.min(Math.max(this.attempts - 1, 0), 16);
        long backoffMillis = Math.min(maxBackoff.toMillis(), baseBackoff.toMillis() * (1L << shift));
        this.nextAttemptAt = now.plusMillis(backoffMillis);
    }

    public UUID getId() {
        return id;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getTopic() {
        return topic;
    }

    public String getPartitionKey() {
        return partitionKey;
    }

    public String getPayload() {
        return payload;
    }

    public String getHeaders() {
        return headers;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public short getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }
}
