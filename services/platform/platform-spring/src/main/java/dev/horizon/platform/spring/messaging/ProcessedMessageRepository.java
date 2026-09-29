package dev.horizon.platform.spring.messaging;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcessedMessageRepository extends JpaRepository<ProcessedMessage, ProcessedMessage.Key> {

    boolean existsByConsumerAndMessageId(String consumer, String messageId);

    /**
     * Claims a message for a consumer.
     *
     * <p>{@code ON CONFLICT DO NOTHING} instead of an insert whose violation is caught: a constraint
     * violation would mark the surrounding transaction rollback-only, turning a benign duplicate
     * into a commit failure and, eventually, a dead-lettered message. The affected-row count carries
     * the same information without that side effect.
     *
     * @return 1 when the claim was taken, 0 when another attempt already holds it
     */
    @Modifying
    @Query(
            value =
                    """
                    INSERT INTO processed_messages (consumer, message_id, processed_at)
                    VALUES (:consumer, :messageId, :processedAt)
                    ON CONFLICT (consumer, message_id) DO NOTHING
                    """,
            nativeQuery = true)
    int claim(
            @Param("consumer") String consumer,
            @Param("messageId") String messageId,
            @Param("processedAt") Instant processedAt);

    @Modifying
    @Query("DELETE FROM ProcessedMessage p WHERE p.processedAt < :before")
    int deleteProcessedBefore(@Param("before") Instant before);
}
