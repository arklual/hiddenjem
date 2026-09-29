package dev.horizon.trends.adapter.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import dev.horizon.trends.config.ResearchProperties;

/**
 * What is left of the hourly budget (BR-A49, P4–P6).
 *
 * <p>New arithmetic over an existing counter, which is the shape of mistake this codebase has made
 * before: two formulas over one number drift, and the one that drifts is the one nobody watches.
 * These tests pin the reading against the rule the charge actually applies.
 *
 * <p>The counter is bucketed by the hour and read through a sliding window, so the moment matters:
 * everything below is anchored at half past, where the previous bucket still counts for half.
 */
class QuotaRemainingTest {

    /** Half past the hour: the previous bucket weighs exactly 0.5, which keeps the sums readable. */
    private static final Instant NOW = Instant.parse("2026-03-01T10:30:00Z");

    private static final UUID USER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID ORGANIZATION = UUID.fromString("22222222-2222-4222-8222-222222222222");

    private final Map<String, String> redisContents = new HashMap<>();
    private boolean redisBroken;

    private RedisQuotaService service(int userLimit, int organizationLimit) {
        var redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call -> {
            if (redisBroken) {
                throw new IllegalStateException("redis недоступен");
            }
            return redisContents.get(call.<String>getArgument(0));
        });
        return new RedisQuotaService(
                redis,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new ResearchProperties(null, null, null, 0, userLimit, organizationLimit, 0, null));
    }

    /** Writes the two buckets the sliding window reads for the given subject. */
    private void used(String prefix, UUID subject, long previousBucket, long currentBucket) {
        long bucket = new SlidingWindow(java.time.Duration.ofHours(1)).bucket(NOW);
        redisContents.put(prefix + subject + ":" + (bucket - 1), Long.toString(previousBucket));
        redisContents.put(prefix + subject + ":" + bucket, Long.toString(currentBucket));
    }

    private void userUsed(long previous, long current) {
        used(RedisQuotaService.USER_KEY_PREFIX, USER, previous, current);
    }

    private void organizationUsed(long previous, long current) {
        used(RedisQuotaService.ORGANIZATION_KEY_PREFIX, ORGANIZATION, previous, current);
    }

    @Test
    void anUntouchedBudgetIsWhole() {
        var budget = service(20, 200).remaining(USER, ORGANIZATION).orElseThrow();

        assertThat(budget.userRemaining()).isEqualTo(20);
        assertThat(budget.organizationRemaining()).isEqualTo(200);
        assertThat(budget.effectiveRemaining()).isEqualTo(20);
    }

    @Test
    void spentSlotsAreSubtracted() {
        userUsed(0, 5);

        assertThat(service(20, 200).remaining(USER, ORGANIZATION).orElseThrow().userRemaining())
                .isEqualTo(15);
    }

    @Test
    void thePreviousHourStillWeighsAtHalfPast() {
        // Окно скользящее, а не «с начала часа»: иначе ровно в 11:00 бюджет обнулялся бы целиком и
        // лимит «двадцать в час» превращался бы в «сорок за две минуты вокруг полуночи».
        userUsed(10, 0);

        assertThat(service(20, 200).remaining(USER, ORGANIZATION).orElseThrow().userRemaining())
                .isEqualTo(15);
    }

    @Test
    void theReadingAgreesWithWhatTheChargeWouldAllow() {
        // Ровно то, ради чего этот файл: остаток — не второй способ посчитать то же самое, а тот же.
        // Здесь потрачено 18.5, значит списание пропустит ещё один запрос и отобьёт следующий.
        userUsed(1, 18);

        var left = service(20, 200).remaining(USER, ORGANIZATION).orElseThrow().userRemaining();

        assertThat(left).isEqualTo(1);
    }

    @Test
    void theSmallerOfTheTwoBudgetsIsTheOneThatStopsYou() {
        // P4. Показать больший значило бы обещать несуществующее: аналитик упрётся в тот, что
        // кончится раньше, — и чаще всего это общий счёт организации, потраченный коллегами.
        organizationUsed(0, 198);

        var budget = service(20, 200).remaining(USER, ORGANIZATION).orElseThrow();

        assertThat(budget.userRemaining()).isEqualTo(20);
        assertThat(budget.organizationRemaining()).isEqualTo(2);
        assertThat(budget.effectiveRemaining()).isEqualTo(2);
    }

    @Test
    void anExhaustedBudgetIsZeroAndNotNegative() {
        userUsed(0, 40);

        assertThat(service(20, 200).remaining(USER, ORGANIZATION).orElseThrow().userRemaining())
                .isZero();
    }

    @Test
    void anUnreadableCounterIsUnknownRatherThanEmpty() {
        // P6/BR-A51. Квота fail-open — запросы всё равно проходят, — поэтому ответить «бюджета нет»
        // значило бы заставить интерфейс утверждать обратное тому, что делает система.
        redisBroken = true;

        assertThat(service(20, 200).remaining(USER, ORGANIZATION)).isEmpty();
    }

    @Test
    void theLimitsReportedAreTheOnesActuallyEnforced() {
        // Показать лимит, отличный от того, по которому отбивают, — обещать чужой бюджет. Нулевой
        // лимит здесь не проверяется: настройки подменяют его умолчанием, то есть такого состояния
        // у работающей системы не бывает, и тест на него проверял бы несуществующее.
        var budget = service(7, 70).remaining(USER, ORGANIZATION).orElseThrow();

        assertThat(budget.userLimit()).isEqualTo(7);
        assertThat(budget.organizationLimit()).isEqualTo(70);
        assertThat(budget.userRemaining()).isEqualTo(7);
    }
}
