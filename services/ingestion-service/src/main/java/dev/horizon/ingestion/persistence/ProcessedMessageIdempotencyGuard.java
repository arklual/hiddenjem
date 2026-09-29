package dev.horizon.ingestion.persistence;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import dev.horizon.ingestion.domain.port.IdempotencyGuard;
import dev.horizon.platform.spring.messaging.ProcessedMessage;
import dev.horizon.platform.spring.messaging.ProcessedMessageRepository;

/**
 * Claims a work unit exactly once, backed by the shared {@code processed_messages} table.
 *
 * <p>The claim is written in the caller's transaction, so "claimed" and "the work committed" are the
 * same fact. A crash after claiming but before committing rolls both back, and the redelivered
 * message is processed normally — the failure mode is a repeat, never a silent skip.
 */
@Component
public class ProcessedMessageIdempotencyGuard implements IdempotencyGuard {

    private static final Logger log = LoggerFactory.getLogger(ProcessedMessageIdempotencyGuard.class);

    private final ProcessedMessageRepository repository;
    private final Clock clock;

    public ProcessedMessageIdempotencyGuard(ProcessedMessageRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public boolean isClaimed(String consumer, String key) {
        return repository.existsByConsumerAndMessageId(consumer, truncate(key));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean claim(String consumer, String key) {
        String messageId = truncate(key);
        if (repository.existsByConsumerAndMessageId(consumer, messageId)) {
            return false;
        }
        try {
            repository.saveAndFlush(new ProcessedMessage(consumer, messageId, clock.instant()));
            return true;
        } catch (DataIntegrityViolationException e) {
            log.debug("Конкурентная заявка на {}:{} проиграна гонку", consumer, messageId);
            return false;
        }
    }

    /** The column is 64 characters; a longer key is hashed rather than silently cut. */
    private static String truncate(String key) {
        if (key == null) {
            throw new IllegalArgumentException("idempotency key must not be null");
        }
        if (key.length() <= 64) {
            return key;
        }
        return dev.horizon.ingestion.domain.support.Hashing.sha256Hex(key);
    }
}
