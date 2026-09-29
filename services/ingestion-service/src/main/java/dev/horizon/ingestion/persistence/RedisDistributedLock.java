package dev.horizon.ingestion.persistence;

import java.time.Duration;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import dev.horizon.ingestion.domain.port.DistributedLock;

/**
 * Best-effort mutual exclusion between replicas (UC-16).
 *
 * <p><b>This is an optimisation, not a correctness mechanism.</b> Collection is idempotent on
 * {@code (sourceId, externalId)}, so two replicas running the same connector produce the same result
 * at worst twice the cost. The lock exists to avoid that waste and to stay polite towards external
 * rate limits — not to protect an invariant. Consequently a Redis outage degrades to "possibly
 * duplicated work", which is acceptable, rather than blocking ingestion entirely.
 *
 * <p>Release is guarded by a fencing token so a lock whose lease already expired is never released
 * by its previous owner — otherwise a slow run could unlock another replica's freshly taken lock.
 */
@Component
public class RedisDistributedLock implements DistributedLock {

    private static final Logger log = LoggerFactory.getLogger(RedisDistributedLock.class);
    private static final String KEY_PREFIX = "lock:connector:";

    private final StringRedisTemplate redis;

    public RedisDistributedLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public boolean runIfAcquired(String key, Duration leaseTime, Runnable action) {
        return Boolean.TRUE.equals(callIfAcquired(key, leaseTime, () -> {
            action.run();
            return Boolean.TRUE;
        }));
    }

    @Override
    public <T> T callIfAcquired(String key, Duration leaseTime, Supplier<T> action) {
        String redisKey = KEY_PREFIX + key;
        String token = UUID.randomUUID().toString();
        Boolean acquired;
        try {
            acquired = redis.opsForValue().setIfAbsent(redisKey, token, leaseTime);
        } catch (RuntimeException e) {
            log.warn("Блокировка {} недоступна ({}), выполняем без неё", key, e.toString());
            return action.get();
        }
        if (!Boolean.TRUE.equals(acquired)) {
            log.debug("Блокировка {} занята другой репликой — прогон пропущен", key);
            return null;
        }
        try {
            return action.get();
        } finally {
            releaseIfOwner(redisKey, token);
        }
    }

    private void releaseIfOwner(String redisKey, String token) {
        try {
            String current = redis.opsForValue().get(redisKey);
            if (token.equals(current)) {
                redis.delete(redisKey);
            }
        } catch (RuntimeException e) {
            // The lease expires on its own; failing to release is harmless.
            log.debug("Не удалось снять блокировку {}: {}", redisKey, e.toString());
        }
    }
}
