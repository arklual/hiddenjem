package dev.horizon.platform.spring.messaging;

import java.time.Clock;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs a message handler at most once per {@code (consumer, messageId)} pair.
 *
 * <p>The dedup row is inserted in the <em>same</em> transaction as the handler's side effects, so
 * either both are committed or neither is.
 *
 * <p>The claim is an {@code INSERT … ON CONFLICT DO NOTHING} rather than a save whose constraint
 * violation is caught. That distinction is load-bearing, not stylistic: a constraint violation
 * inside a JPA transaction marks it rollback-only, so catching the exception and returning normally
 * would make the caller's commit fail with {@code UnexpectedRollbackException}. The listener would
 * then treat a perfectly benign duplicate as a processing failure and eventually dead-letter it —
 * the exact outcome this class exists to prevent. Branching on the affected-row count never poisons
 * the transaction, so a concurrent duplicate is simply reported as "already handled".
 */
public class IdempotentMessageProcessor {

    private static final Logger log = LoggerFactory.getLogger(IdempotentMessageProcessor.class);

    private final ProcessedMessageRepository repository;
    private final Clock clock;

    public IdempotentMessageProcessor(ProcessedMessageRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * @return {@code true} when the handler ran, {@code false} when the message was a duplicate
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public <T> boolean processOnce(String consumer, String messageId, T message, Consumer<T> handler) {
        if (repository.claim(consumer, messageId, clock.instant()) == 0) {
            log.debug("Skipping duplicate message {} for consumer {}", messageId, consumer);
            return false;
        }
        handler.accept(message);
        return true;
    }

    /** Whether this consumer has already handled the message, without claiming it. */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public boolean alreadyProcessed(String consumer, String messageId) {
        return repository.existsByConsumerAndMessageId(consumer, messageId);
    }
}
