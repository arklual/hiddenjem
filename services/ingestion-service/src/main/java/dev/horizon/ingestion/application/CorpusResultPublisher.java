package dev.horizon.ingestion.application;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.horizon.ingestion.domain.event.CorpusCollected;
import dev.horizon.ingestion.domain.event.CorpusCollectionFailed;
import dev.horizon.ingestion.domain.event.CorpusCollectionProgressed;
import dev.horizon.ingestion.domain.port.CorpusSnapshotRepository;
import dev.horizon.ingestion.domain.port.IdempotencyGuard;
import dev.horizon.ingestion.domain.snapshot.CorpusSnapshot;
import dev.horizon.platform.common.event.DomainEventPublisher;

/**
 * Emits the single terminal event of a corpus collection, exactly once per
 * {@code (researchRequestId, attempt)}.
 *
 * <p>The claim and the outbox write share one transaction, which is what makes "exactly once"
 * true: either both commit or neither does. If a duplicate command arrives, the claim fails, no
 * event is written, and the saga is not woken twice.
 *
 * <p>Note the ordering of the whole flow: the <em>work</em> (fetching and persisting documents) runs
 * before the claim, not after. Work is idempotent — unique constraints absorb repeats — whereas
 * claiming first would mean a crash between claim and event leaves the saga waiting forever.
 */
@Service
public class CorpusResultPublisher {

    static final String CONSUMER = "ingestion.collect-domain-corpus";

    private static final Logger log = LoggerFactory.getLogger(CorpusResultPublisher.class);

    private final IdempotencyGuard idempotency;
    private final CorpusSnapshotRepository snapshots;
    private final DomainEventPublisher publisher;
    private final Clock clock;

    public CorpusResultPublisher(
            IdempotencyGuard idempotency,
            CorpusSnapshotRepository snapshots,
            DomainEventPublisher publisher,
            Clock clock) {
        this.idempotency = idempotency;
        this.snapshots = snapshots;
        this.publisher = publisher;
        this.clock = clock;
    }

    /** Read-only pre-check so a duplicate command does not re-crawl the internet for nothing. */
    public boolean alreadyPublished(String idempotencyKey) {
        return idempotency.isClaimed(CONSUMER, idempotencyKey);
    }

    /** @return {@code true} when the event was written, {@code false} when it was a duplicate */
    @Transactional
    public boolean publishCollected(
            String idempotencyKey, UUID researchRequestId, int attempt, CorpusSnapshot snapshot) {
        if (!idempotency.claim(CONSUMER, idempotencyKey)) {
            log.info("CorpusCollected for {} attempt {} was already published — skipping", researchRequestId, attempt);
            return false;
        }
        // The snapshot is persisted in the same transaction as the event that announces it.
        // Without this, a consumer could receive a snapshot id it can never resolve back to a
        // document set — and reproducibility (ADR-0015) would be a claim rather than a property.
        snapshots.save(snapshot, researchRequestId, attempt);
        publisher.publish(CorpusCollected.of(researchRequestId, attempt, snapshot, clock.instant()));
        log.info(
                "CorpusCollected: request={} attempt={} documents={} partial={} hash={}",
                researchRequestId,
                attempt,
                snapshot.documentCount(),
                snapshot.partial(),
                snapshot.contentHash());
        return true;
    }

    /** @return {@code true} when the event was written, {@code false} when it was a duplicate */
    @Transactional
    public boolean publishFailed(
            String idempotencyKey,
            UUID researchRequestId,
            int attempt,
            String code,
            String message,
            boolean retryable,
            Map<String, Object> details) {
        if (!idempotency.claim(CONSUMER, idempotencyKey)) {
            log.info(
                    "Collection outcome for {} attempt {} was already published — skipping",
                    researchRequestId,
                    attempt);
            return false;
        }
        publisher.publish(CorpusCollectionFailed.of(
                researchRequestId, attempt, code, message, retryable, details, clock.instant()));
        log.warn("CorpusCollectionFailed: request={} attempt={} code={} {}", researchRequestId, attempt, code, message);
        return true;
    }

    /**
     * Ход сбора — без заявки на идемпотентность: событие информационное, повтор после
     * переотправленной команды лишь ещё раз сообщит то же самое. Своя транзакция на каждое, чтобы
     * ход уходил, пока сбор идёт, а не весь разом в конце.
     */
    @Transactional
    public void publishProgress(
            UUID researchRequestId,
            int attempt,
            int percent,
            String message,
            int sourcesDone,
            int sourcesTotal,
            int documentCount) {
        publisher.publish(CorpusCollectionProgressed.of(
                researchRequestId,
                attempt,
                percent,
                message,
                sourcesDone,
                sourcesTotal,
                documentCount,
                clock.instant()));
    }
}
