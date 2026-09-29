package dev.horizon.trends.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import dev.horizon.trends.domain.research.AnalysisMode;
import dev.horizon.trends.domain.research.AnalysisParameters;
import dev.horizon.trends.domain.research.ResearchRequest;
import dev.horizon.trends.support.Fixtures;

/**
 * Режим в строке запроса и строки, записанные до него.
 *
 * <p>Главное здесь — ключ параметров старой строки. Он оканчивается именем выведенного движка
 * ({@code …|methodology}), и пока это так, проверка свежести не выдаст её отчёт ответом на новый
 * вопрос. Пересчитай маппер ключ при очередном сохранении — позднем результате, таймауте, — и строка
 * тихо станет находимой под новым ключом.
 */
class ResearchRequestModeMappingTest {

    private final ResearchRequestMapper mapper = new ResearchRequestMapper();

    private static ResearchRequest request(AnalysisMode mode) {
        var request = ResearchRequest.submit(
                Fixtures.requester(),
                Fixtures.query(),
                AnalysisParameters.defaults(Fixtures.PROFILE_ID, mode),
                null,
                Duration.ofMinutes(20),
                Fixtures.NOW);
        request.drainEvents();
        return request;
    }

    @Test
    void theModeSurvivesTheRoundTrip() {
        var entity = mapper.toEntity(request(AnalysisMode.QUALITY), null);

        assertThat(entity.getMode()).isEqualTo("quality");
        assertThat(entity.getParamsDiscriminator()).endsWith("|quality");
        assertThat(mapper.toDomain(entity).parameters().mode()).isEqualTo(AnalysisMode.QUALITY);
    }

    @Test
    void aRowWithoutAModeIsFast() {
        var entity = mapper.toEntity(request(AnalysisMode.FAST), null);
        entity.setMode(null);

        assertThat(mapper.toDomain(entity).parameters().mode()).isEqualTo(AnalysisMode.FAST);
    }

    @Test
    void aLegacyRowKeepsItsKeyWhenSavedAgain() {
        var legacy = mapper.toEntity(request(AnalysisMode.FAST), null);
        var oldKey = "15|7|*|0.000|false|" + Fixtures.PROFILE_ID + "|methodology";
        legacy.setParamsDiscriminator(oldKey);

        var reloaded = mapper.toDomain(legacy);
        mapper.toEntity(reloaded, legacy);

        assertThat(legacy.getParamsDiscriminator()).isEqualTo(oldKey);
    }
}
