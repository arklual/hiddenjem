package dev.horizon.trends.adapter.cache;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import dev.horizon.platform.common.error.HorizonException;
import dev.horizon.trends.application.port.QuotaService;
import dev.horizon.trends.config.ResearchProperties;

/**
 * Per-user and per-organisation request quotas (FR-02.6, BR-D4).
 *
 * <p>Counters live in Redis with the key layout of data-model §5 ({@code rl:user:{id}:{window}}),
 * evaluated as a sliding-window counter — see {@link SlidingWindow} for why a fixed window would let
 * a user spend two budgets in two minutes.
 *
 * <p><b>Fail-open, deliberately.</b> If Redis is unavailable the request is allowed and a WARN is
 * logged. The trade-off is explicit: a quota exists to protect the analysis pipeline from a runaway
 * client, not to protect correctness, so during a cache outage the right failure is "a few extra
 * analyses run" rather than "the product stops accepting work". The alternative — failing closed —
 * would turn a degraded dependency into a total outage of the platform's primary use case. The
 * database-backed counters ({@code countSubmittedSince}) remain the auditable record.
 */
public class RedisQuotaService implements QuotaService {

    private static final Logger log = LoggerFactory.getLogger(RedisQuotaService.class);

    public static final String USER_KEY_PREFIX = "rl:user:";
    public static final String ORGANIZATION_KEY_PREFIX = "rl:org:";

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final SlidingWindow window;
    private final int userLimit;
    private final int organizationLimit;

    public RedisQuotaService(StringRedisTemplate redis, Clock clock, ResearchProperties properties) {
        this.redis = redis;
        this.clock = clock;
        this.window = new SlidingWindow(Duration.ofHours(1));
        this.userLimit = properties.userQuotaPerHour();
        this.organizationLimit = properties.organizationQuotaPerHour();
    }

    @Override
    public void checkAndConsume(UUID userId, UUID organizationId) {
        var now = clock.instant();
        try {
            consume(USER_KEY_PREFIX + userId, userLimit, now, "Превышена персональная квота запросов (%d в час)");
            try {
                consume(
                        ORGANIZATION_KEY_PREFIX + organizationId,
                        organizationLimit,
                        now,
                        "Превышена квота организации (%d в час)");
            } catch (HorizonException e) {
                // The user's budget was already charged; give it back so a rejection caused by a
                // colleague's usage does not silently cost this user one of their own requests.
                decrement(USER_KEY_PREFIX + userId, now);
                throw e;
            }
        } catch (HorizonException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Redis недоступен, проверка квот пропущена (fail-open): {}", e.getMessage());
        }
    }

    @Override
    public void refund(UUID userId, UUID organizationId, Instant chargedAt) {
        try {
            decrement(USER_KEY_PREFIX + userId, chargedAt);
            decrement(ORGANIZATION_KEY_PREFIX + organizationId, chargedAt);
        } catch (Exception e) {
            log.warn("Не удалось вернуть квоту (fail-open): {}", e.getMessage());
        }
    }

    @Override
    public Optional<QuotaBudget> remaining(UUID userId, UUID organizationId) {
        try {
            var now = clock.instant();
            return Optional.of(new QuotaBudget(
                    left(USER_KEY_PREFIX + userId, userLimit, now),
                    userLimit,
                    left(ORGANIZATION_KEY_PREFIX + organizationId, organizationLimit, now),
                    organizationLimit));
        } catch (Exception e) {
            // Empty, not zero. The counter is fail-open — requests still go through when Redis is
            // down — so answering "нет бюджета" would make the interface contradict the system.
            log.warn("Redis недоступен, остаток квоты неизвестен: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Read through the same sliding window the charge uses: two formulas over one counter drift. */
    private int left(String prefix, int limit, Instant now) {
        long currentBucket = window.bucket(now);
        double used = window.estimate(
                readCount(prefix + ":" + (currentBucket - 1)), readCount(prefix + ":" + currentBucket), now);
        return (int) Math.max(0, Math.floor(limit - used));
    }

    private void consume(String prefix, int limit, Instant now, String messageTemplate) {
        long currentBucket = window.bucket(now);
        String currentKey = prefix + ":" + currentBucket;
        String previousKey = prefix + ":" + (currentBucket - 1);

        long previousCount = readCount(previousKey);
        // INCR first, then evaluate: doing it the other way round leaves a window in which two
        // concurrent requests both read "one below the limit" and both proceed.
        Long current = redis.opsForValue().increment(currentKey);
        long currentCount = current == null ? 1L : current;
        redis.expire(currentKey, window.retention());

        if (window.estimate(previousCount, currentCount, now) > limit) {
            redis.opsForValue().decrement(currentKey);
            throw HorizonException.quotaExceeded(messageTemplate.formatted(limit));
        }
    }

    private void decrement(String prefix, Instant now) {
        String currentKey = prefix + ":" + window.bucket(now);
        Long remaining = redis.opsForValue().decrement(currentKey);
        if (remaining != null && remaining < 0) {
            // A refund without a matching charge (e.g. after a restart) must not create credit.
            // Logged because the floor is otherwise indistinguishable from an ordinary refund, and
            // the two mean different things: one is routine, the other says a charge went missing.
            log.warn("Возврат квоты без списания по ключу {} — счётчик оставлен на нуле", currentKey);
            redis.opsForValue().set(currentKey, "0", window.retention());
        }
    }

    private long readCount(String key) {
        String value = redis.opsForValue().get(key);
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
