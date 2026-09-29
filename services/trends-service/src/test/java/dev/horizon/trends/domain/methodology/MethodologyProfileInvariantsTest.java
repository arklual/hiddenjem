package dev.horizon.trends.domain.methodology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Профиль методологии: что именно нельзя собрать.
 *
 * <p>Профиль задаёт веса индикаторов в команде анализа. Создавать профили из продукта больше нельзя
 * — экран методологии выведен, — но профиль по-прежнему читается из базы, куда его может положить
 * миграция, и инварианты проверяются при каждом восстановлении. Ловушка ниже от этого не исчезла.
 *
 * <p><b>Нулевой вес молча отключает BRULE-4.</b> Правило «нулевой индикатор обнуляет балл» держится
 * на произведении {@code x^w}: при {@code w = 0} множитель равен единице независимо от {@code x}.
 * Профиль с {@code weakness = 0} снял бы гарантию «сигнал ещё не мейнстрим» — ту самую, ради которой
 * продукт и существует, — не сказав об этом ни строчкой.
 *
 * <p>Намерение «этот индикатор для моей области почти не важен» выражается малым весом и остаётся
 * доступным. Невыразимым стало только намерение отключить правило: оно и не выражалось, а получалось
 * побочным эффектом.
 */
class MethodologyProfileInvariantsTest {

    private static final UUID ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");

    private static Map<String, Double> weights(double novelty, double growth, double rest) {
        var map = new LinkedHashMap<String, Double>();
        map.put("novelty", novelty);
        map.put("growth", growth);
        map.put("diffusion", rest);
        map.put("weakness", rest);
        map.put("coherence", rest);
        map.put("impact", rest);
        return map;
    }

    private static MethodologyProfile profile(Map<String, Double> weights) {
        return new MethodologyProfile(
                ID,
                "Профиль биотеха",
                1,
                "em-1.0.0",
                MethodologyProfile.ScoreAggregator.WEIGHTED_GEOMETRIC,
                weights,
                Map.of(),
                0.4,
                false,
                null,
                NOW);
    }

    @Test
    @DisplayName("вес ноль отвергается: он отключает BRULE-4, а не уменьшает влияние")
    void aZeroWeightIsRejectedBecauseItSwitchesTheZeroRuleOff() {
        var weights = weights(0.25, 0.35, 0.10);
        weights.put("weakness", 0.0);
        weights.put("impact", 0.20);

        assertThatThrownBy(() -> profile(weights))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("weakness")
                .hasMessageContaining("BRULE-4");
    }

    @Test
    @DisplayName("малый вес разрешён: «почти не важен» — законное намерение")
    void aTinyWeightIsAllowedBecauseItIsADifferentIntention() {
        var weights = weights(0.30, 0.44, 0.06);
        weights.put("impact", 0.08);

        assertThatCode(() -> profile(weights)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("сумма весов обязана равняться единице")
    void theWeightsMustSumToOne() {
        assertThatThrownBy(() -> profile(weights(0.30, 0.30, 0.30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Сумма весов");
    }

    @Test
    @DisplayName("веса хранятся в порядке имён — сериализация побайтово воспроизводима")
    void theWeightsAreStoredInAStableOrder() {
        // Порядок ключей карты попадает в JSON профиля и в сравнение отчётов. Случайный порядок
        // сделал бы два одинаковых профиля различимыми побайтово, а ADR-0015 требует обратного.
        var built = profile(weights(0.20, 0.30, 0.125));

        assertThat(built.weights().keySet())
                .containsExactlyInAnyOrderElementsOf(
                        java.util.List.of("novelty", "growth", "diffusion", "weakness", "coherence", "impact"));
    }

    @Test
    @DisplayName("веса по умолчанию сами проходят все инварианты")
    void theDefaultWeightsSatisfyTheirOwnRules() {
        // Канарейка: правило, которому не удовлетворяет поставляемый профиль, — сломанное правило.
        assertThatCode(() -> profile(new LinkedHashMap<>(MethodologyProfile.defaultWeights())))
                .doesNotThrowAnyException();
    }
}
