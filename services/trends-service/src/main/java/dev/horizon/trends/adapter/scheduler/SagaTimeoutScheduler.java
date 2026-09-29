package dev.horizon.trends.adapter.scheduler;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import dev.horizon.trends.adapter.cache.RedisLock;
import dev.horizon.trends.application.saga.ResearchSaga;
import dev.horizon.trends.config.ResearchProperties;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Fails requests that blew their deadline (FR-02.5).
 *
 * <p>Without this, a request whose downstream step never reports back would sit in {@code ANALYZING}
 * forever: nothing else in the system has a reason to touch it, and the user would watch a progress
 * bar that will never move. The sweeper turns that into an explicit, retryable {@code SAGA_TIMEOUT}
 * failure the UI can act on.
 *
 * <p>The Redis lock stops every replica doing the same sweep at the same moment — but it is only an
 * optimisation, and the design does not depend on it. {@code failOverdue} re-reads each candidate
 * under a row lock and re-checks the deadline before acting, so two replicas running concurrently
 * produce exactly the same outcome as one; the second simply finds nothing to do. That is why the
 * lock is allowed to fail open (see {@link RedisLock}).
 *
 * <p>Fixed <em>delay</em>, not fixed rate: the interval is measured from the end of the previous run,
 * so a slow sweep cannot queue up overlapping executions.
 */
@Component
@ConditionalOnProperty(
        prefix = "horizon.research",
        name = "timeout-sweeper-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class SagaTimeoutScheduler {

    private static final Logger log = LoggerFactory.getLogger(SagaTimeoutScheduler.class);

    private static final String LOCK_KEY = "lock:trends:saga-timeout";

    private final ResearchSaga saga;
    private final RedisLock lock;
    private final int batchSize;
    private final Duration lockTtl;
    private final Counter timedOut;

    public SagaTimeoutScheduler(
            ResearchSaga saga,
            RedisLock lock,
            ResearchProperties properties,
            @Value("${horizon.research.timeout-sweep-lock-ttl:PT1M}") Duration lockTtl,
            MeterRegistry meterRegistry) {
        this.saga = saga;
        this.lock = lock;
        this.batchSize = properties.timeoutSweepBatchSize();
        this.lockTtl = lockTtl;
        // Просроченная сага — единственный исход, о котором пользователь узнаёт как об отказе, не
        // получив ни ошибки источника, ни ошибки движка. Всплеск таких завершений означает, что
        // сломалось что-то выше по конвейеру, и увидеть его можно только по счётчику.
        this.timedOut = Counter.builder("horizon.saga.timeouts")
                .description("Research requests failed by the saga deadline sweeper")
                .register(meterRegistry);
    }

    @Scheduled(
            fixedDelayString = "${horizon.research.timeout-scan-interval:PT30S}",
            initialDelayString = "${horizon.research.timeout-scan-initial-delay:PT30S}")
    public void sweep() {
        String token = lock.tryAcquire(LOCK_KEY, lockTtl);
        if (token == null) {
            log.trace("Подметание таймаутов уже выполняется на другой реплике");
            return;
        }
        try {
            int failed = saga.failOverdue(batchSize);
            if (failed > 0) {
                timedOut.increment(failed);
                log.info("Завершено по таймауту саги: {} запрос(ов)", failed);
            }
        } catch (Exception e) {
            // A scheduled method that throws is silently dropped by the executor; logging here is
            // the only way an operator ever learns the sweeper is broken.
            log.error("Ошибка при подметании просроченных запросов", e);
        } finally {
            lock.release(LOCK_KEY, token);
        }
    }
}
