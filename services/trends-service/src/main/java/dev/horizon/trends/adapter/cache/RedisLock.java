package dev.horizon.trends.adapter.cache;

import java.time.Duration;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Best-effort mutual exclusion between replicas (ADR-0011).
 *
 * <p><b>Not</b> a distributed lock in the strict sense, and ADR-0011 says so explicitly: a single
 * Redis with no fencing token cannot prevent two holders during a failover or a long GC pause.
 * Everything guarded by it must therefore be idempotent on its own, and the lock is only an
 * optimisation that stops N replicas doing identical work N times.
 *
 * <p>Consequently the failure mode is fail-open: if Redis is down, {@link #tryAcquire} returns true
 * and the work runs everywhere. Duplicate idempotent work beats no work.
 */
public class RedisLock {

    private static final Logger log = LoggerFactory.getLogger(RedisLock.class);

    private final StringRedisTemplate redis;

    public RedisLock(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** @return a token to pass to {@link #release}, or {@code null} if a peer holds the lock */
    public String tryAcquire(String key, Duration ttl) {
        String token = UUID.randomUUID().toString();
        try {
            Boolean acquired = redis.opsForValue().setIfAbsent(key, token, ttl);
            return Boolean.TRUE.equals(acquired) ? token : null;
        } catch (Exception e) {
            log.warn("Redis недоступен, блокировка '{}' пропущена (работа идемпотентна): {}", key, e.getMessage());
            return token;
        }
    }

    /**
     * Releases the lock only if this holder still owns it.
     *
     * <p>The compare before the delete matters: without it, a holder whose TTL expired mid-work would
     * delete the lock a <em>different</em> replica had since acquired, defeating the whole point.
     * This read-then-delete is not atomic, which is acceptable for a best-effort lock; making it
     * atomic would need a Lua script and buy nothing given the caveat above.
     */
    public void release(String key, String token) {
        if (token == null) {
            return;
        }
        try {
            if (token.equals(redis.opsForValue().get(key))) {
                redis.delete(key);
            }
        } catch (Exception e) {
            log.debug("Не удалось освободить блокировку '{}': {}", key, e.getMessage());
        }
    }
}
