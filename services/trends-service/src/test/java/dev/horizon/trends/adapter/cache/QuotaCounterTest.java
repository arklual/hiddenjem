package dev.horizon.trends.adapter.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import dev.horizon.platform.common.error.HorizonException;
import dev.horizon.trends.config.ResearchProperties;

/**
 * Сам счётчик квоты: списание, возврат и два правила, которые до сих пор жили только в комментариях.
 *
 * <p>Возврат проверялся на уровне сценария — «неудавшийся запрос возвращает квоту» — с подставным
 * сервисом квот. Сам счётчик не проверялся ничем, и подмена это показала: обе порчи ниже проходили
 * мимо всех тестов модуля.
 *
 * <p>Цена у обеих несимметрична обычной ошибке ранжирования.
 *
 * <p><b>Возврат без списания не создаёт кредит.</b> Счётчик, ушедший в минус, не восстанавливается
 * сам: лимит перестаёт связывать этого пользователя до конца часа, а после перезапуска сервиса,
 * когда возвраты приходят к несуществующим списаниям, — систематически.
 *
 * <p><b>Отказ по квоте организации не стоит пользователю его личного запроса.</b> Личный бюджет
 * списывается первым, и если следом отказала организация, списание обязано откатиться. Иначе
 * пользователь платит за нагрузку коллег, не получив ничего, и по интерфейсу это неотличимо от
 * его собственного расхода.
 */
class QuotaCounterTest {

    /** Половина часа: предыдущее ведро весит ровно 0.5, суммы остаются читаемыми. */
    private static final Instant NOW = Instant.parse("2026-03-01T10:30:00Z");

    private static final UUID USER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID ORGANIZATION = UUID.fromString("22222222-2222-4222-8222-222222222222");

    private final Map<String, Long> counters = new HashMap<>();

    /** Счётчик Redis с теми операциями, которыми пользуется сервис: INCR, DECR, SET, EXPIRE, GET. */
    private RedisQuotaService service(int userLimit, int organizationLimit) {
        var redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call -> {
            Long value = counters.get(call.<String>getArgument(0));
            return value == null ? null : String.valueOf(value);
        });
        when(values.increment(anyString())).thenAnswer(call -> counters.merge(call.getArgument(0), 1L, Long::sum));
        when(values.decrement(anyString())).thenAnswer(call -> counters.merge(call.getArgument(0), -1L, Long::sum));
        when(redis.expire(anyString(), any(Duration.class))).thenReturn(true);
        // SET пишет строку: сервис пользуется им, чтобы вернуть счётчик на ноль.
        org.mockito.Mockito.doAnswer(call -> {
                    counters.put(call.getArgument(0), Long.parseLong(call.getArgument(1)));
                    return null;
                })
                .when(values)
                .set(anyString(), anyString(), any(Duration.class));
        return new RedisQuotaService(
                redis,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new ResearchProperties(null, null, null, 0, userLimit, organizationLimit, 0, null));
    }

    private long counter(String prefix, UUID subject) {
        long bucket = NOW.getEpochSecond() / Duration.ofHours(1).getSeconds();
        return counters.getOrDefault(prefix + subject + ":" + bucket, 0L);
    }

    private long userCounter() {
        return counter(RedisQuotaService.USER_KEY_PREFIX, USER);
    }

    private long organizationCounter() {
        return counter(RedisQuotaService.ORGANIZATION_KEY_PREFIX, ORGANIZATION);
    }

    @Test
    @DisplayName("списание увеличивает оба счётчика")
    void aChargeCountsAgainstBothBudgets() {
        var service = service(10, 10);

        service.checkAndConsume(USER, ORGANIZATION);

        assertThat(userCounter()).isEqualTo(1);
        assertThat(organizationCounter()).isEqualTo(1);
    }

    @Test
    @DisplayName("возврат отменяет своё списание, а не уменьшает счётчик сверх него")
    void aRefundUndoesItsOwnChargeAndNoMore() {
        var service = service(10, 10);
        service.checkAndConsume(USER, ORGANIZATION);

        service.refund(USER, ORGANIZATION, NOW);

        assertThat(userCounter()).isZero();
        assertThat(organizationCounter()).isZero();
    }

    @Test
    @DisplayName("возврат без списания не создаёт кредит")
    void aRefundWithoutAChargeDoesNotCreateCredit() {
        // Так приходит возврат после перезапуска сервиса: списание было в памяти прежнего
        // процесса, возврат приходит к пустому счётчику. Минус на счётчике не восстанавливается
        // сам — лимит перестал бы связывать этого пользователя до конца часа.
        var service = service(10, 10);

        service.refund(USER, ORGANIZATION, NOW);

        assertThat(userCounter()).isZero();
        assertThat(organizationCounter()).isZero();
    }

    @Test
    @DisplayName("отказ по квоте организации не стоит пользователю его личного запроса")
    void aRejectionCausedByColleaguesDoesNotSpendYourOwnBudget() {
        // Организация исчерпана, у пользователя бюджет цел. Личный счётчик списывается первым, и
        // если следом отказала организация, списание обязано откатиться: иначе пользователь
        // платит за чужую нагрузку, не получив ничего.
        var service = service(10, 1);
        service.checkAndConsume(USER, ORGANIZATION);
        long userSpentBefore = userCounter();

        assertThatThrownBy(() -> service.checkAndConsume(USER, ORGANIZATION))
                .isInstanceOf(HorizonException.class)
                .hasMessageContaining("организации");

        assertThat(userCounter())
                .as("личный счётчик после отказа по организации")
                .isEqualTo(userSpentBefore);
    }

    @Test
    @DisplayName("отказ по личной квоте не расходует бюджет организации")
    void aRejectionOnYourOwnBudgetLeavesTheOrganizationUntouched() {
        // Обратная сторона того же правила: личный лимит проверяется первым, и до счётчика
        // организации дело не доходит вовсе.
        var service = service(1, 10);
        service.checkAndConsume(USER, ORGANIZATION);
        long organizationSpentBefore = organizationCounter();

        assertThatThrownBy(() -> service.checkAndConsume(USER, ORGANIZATION)).isInstanceOf(HorizonException.class);

        assertThat(organizationCounter()).isEqualTo(organizationSpentBefore);
    }
}
