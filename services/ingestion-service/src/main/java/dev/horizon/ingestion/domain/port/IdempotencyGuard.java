package dev.horizon.ingestion.domain.port;

/**
 * At-most-once effects for at-least-once delivery (ADR-0003).
 *
 * <p>The key here is a <em>business</em> key — {@code (researchRequestId, attempt)} — not the
 * broker's message id. That is stronger than message-level deduplication: a redelivery, a
 * re-publication by the sender and a manual replay all carry different message ids but describe the
 * same unit of work, and only one {@code CorpusCollected} may ever be emitted for it (FR-05.6).
 *
 * <p>{@link #claim} must insert its marker in the caller's transaction, so "claimed" and "event
 * written to the outbox" commit together or not at all.
 */
public interface IdempotencyGuard {

    /** Cheap read-only pre-check; a {@code true} answer lets the caller skip the work entirely. */
    boolean isClaimed(String consumer, String key);

    /**
     * Claims the key inside the current transaction.
     *
     * @return {@code true} when this caller won the claim and must perform the effect
     */
    boolean claim(String consumer, String key);
}
