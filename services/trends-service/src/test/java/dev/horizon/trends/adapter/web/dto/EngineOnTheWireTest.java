package dev.horizon.trends.adapter.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizon.trends.application.dto.AnalysisResult;
import dev.horizon.trends.support.Fixtures;

/**
 * Подпись движка на границе с движком: поле обязано её пережить.
 *
 * <p>Выбирать движок больше нельзя, но отчёт по-прежнему говорит, чем посчитан. У события движка своя
 * модель, и добавленное поле в ней не появляется само; потеря ничего не роняет — отчёт собирается, и
 * разница видна только в подписи.
 */
class EngineOnTheWireTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void theAnalysisEventNamesTheEngineThatProducedTheReport() throws Exception {
        var result = json.readValue(
                """
                {"researchRequestId": "11111111-1111-4111-8111-111111111111",
                 "attempt": 1,
                 "snapshotId": "22222222-2222-4222-8222-222222222222",
                 "engine": "signals",
                 "methodologyVersion": "em-1.0.0",
                 "profileId": "33333333-3333-4333-8333-333333333333",
                 "aggregator": "WEIGHTED_GEOMETRIC",
                 "trends": []}
                """,
                AnalysisResult.class);

        assertThat(result.engineOrDefault()).isEqualTo("signals");
    }

    @Test
    void anEventWithoutAnEngineIsAMethodologyReport() throws Exception {
        // Событие, выпущенное до появления второго движка, поля не несёт — и других посчитанных
        // тогда не было.
        var result = json.readValue(
                """
                {"researchRequestId": "11111111-1111-4111-8111-111111111111",
                 "attempt": 1,
                 "snapshotId": "22222222-2222-4222-8222-222222222222",
                 "methodologyVersion": "em-1.0.0",
                 "profileId": "33333333-3333-4333-8333-333333333333",
                 "aggregator": "WEIGHTED_GEOMETRIC",
                 "trends": []}
                """,
                AnalysisResult.class);

        assertThat(result.engineOrDefault()).isEqualTo("methodology");
    }

    @Test
    void anEngineThisServiceDoesNotKnowIsPassedThroughAsIs() throws Exception {
        // Движок вправе быть новее этого сервиса. Единственное, что сервис делает с этим полем, —
        // сохраняет и показывает; подменять чужое имя своим значило бы врать о происхождении.
        var result = new AnalysisResult(
                "11111111-1111-4111-8111-111111111111",
                1,
                "22222222-2222-4222-8222-222222222222",
                "neural-oracle",
                "em-1.0.0",
                Fixtures.PROFILE_ID.toString(),
                "WEIGHTED_GEOMETRIC",
                null,
                0,
                0,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of(),
                List.of());

        assertThat(result.engineOrDefault()).isEqualTo("neural-oracle");
    }
}
