package dev.horizon.platform.spring.messaging;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxMessageRepository extends JpaRepository<OutboxMessage, UUID> {

    /**
     * Claims a batch of pending messages.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} is what makes horizontal scaling of the publisher safe:
     * concurrent replicas skip rows already claimed by a peer instead of blocking, so throughput
     * grows with replica count and no message is delivered twice by two publishers in the same
     * instant.
     *
     * <p>The {@code attempts < :maxAttempts} predicate is load-bearing, not cosmetic. A parked row
     * (one that exhausted its retries) keeps {@code published_at IS NULL} and a {@code
     * next_attempt_at} frozen in the past, so without this clause it would be re-claimed on every
     * poll — and because the batch is ordered by age, a handful of parked rows would occupy the
     * whole batch and the service would silently stop publishing anything at all.
     *
     * <p>Ordering is by {@code next_attempt_at} first so the partial index on that column can serve
     * the sort instead of the planner filtering and then sorting.
     */
    @Query(
            value =
                    """
                    SELECT * FROM outbox_messages
                    WHERE published_at IS NULL
                      AND next_attempt_at <= :now
                      AND attempts < :maxAttempts
                    ORDER BY next_attempt_at, created_at
                    LIMIT :batchSize
                    FOR UPDATE SKIP LOCKED
                    """,
            nativeQuery = true)
    List<OutboxMessage> claimBatch(
            @Param("now") Instant now, @Param("batchSize") int batchSize, @Param("maxAttempts") int maxAttempts);

    /** Rows that gave up. Non-zero means an operator has to look — it is not self-healing. */
    @Query("SELECT count(m) FROM OutboxMessage m WHERE m.publishedAt IS NULL AND m.attempts >= :maxAttempts")
    long countParked(@Param("maxAttempts") int maxAttempts);

    long countByPublishedAtIsNull();

    /**
     * When the oldest still-unpublished message was written, or {@code null} when there is none.
     *
     * <p>Backlog size and backlog age answer different questions. A thousand messages published
     * within a second are healthy; one message stuck for five minutes is an outage of the saga that
     * depends on it, and a count-based alert cannot see it at all.
     */
    @Query("SELECT min(m.createdAt) FROM OutboxMessage m WHERE m.publishedAt IS NULL")
    Instant oldestPendingCreatedAt();

    @Modifying
    @Query("DELETE FROM OutboxMessage m WHERE m.publishedAt IS NOT NULL AND m.publishedAt < :before")
    int deletePublishedBefore(@Param("before") Instant before);
}
