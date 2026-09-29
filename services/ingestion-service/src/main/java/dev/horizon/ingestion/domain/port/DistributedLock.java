package dev.horizon.ingestion.domain.port;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Mutual exclusion between replicas for scheduled work (UC-16, Redis key
 * {@code lock:connector:{sourceId}}).
 *
 * <p><b>Correctness does not depend on this lock.</b> Ingestion is idempotent — documents are keyed
 * by {@code (sourceId, externalId)} and by {@code dedupKey}, and events go through the outbox — so
 * two replicas crawling the same source concurrently produce duplicate <em>work</em>, never
 * duplicate <em>data</em>. The lock exists to be polite to the source and to keep run history
 * readable. Consequently a lock service that is down must not stop ingestion: implementations fail
 * open and log, rather than throwing.
 */
public interface DistributedLock {

    /**
     * Runs {@code action} while holding {@code key}, or skips it if another holder has the lock.
     *
     * @return {@code true} when the action ran
     */
    boolean runIfAcquired(String key, Duration leaseTime, Runnable action);

    /** Variant returning the action's value; {@code null} when the lock was not acquired. */
    <T> T callIfAcquired(String key, Duration leaseTime, Supplier<T> action);
}
