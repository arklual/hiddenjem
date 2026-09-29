package dev.horizon.trends.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import dev.horizon.trends.adapter.web.mapper.ReportViewMapper;
import dev.horizon.trends.config.FeatureFlags;
import dev.horizon.trends.config.FeatureGate;
import dev.horizon.trends.domain.report.MethodologyRef;
import dev.horizon.trends.domain.research.AnalysisMode;
import dev.horizon.trends.domain.research.AnalysisParameters;
import dev.horizon.trends.domain.research.ResearchRequest;
import dev.horizon.trends.support.Fixtures;

/**
 * Отчёт говорит, в каком режиме посчитан, — и говорит это сам, а не через запрос.
 *
 * <p>Режим берётся у запроса в момент сборки и дальше живёт в отчёте: отчёт неизменяем и читается
 * без своего запроса (из кэша, по ссылке коллеги), а вопрос «почему этот отчёт беднее того» без
 * режима на странице не имеет ответа.
 */
class ReportCarriesItsModeTest {

    private final ReportViewMapper views =
            new ReportViewMapper(new FeatureGate(new FeatureFlags(new MockEnvironment())));

    private static ResearchRequest assembling(AnalysisMode mode) {
        var request = ResearchRequest.submit(
                Fixtures.requester(),
                Fixtures.query(),
                AnalysisParameters.defaults(Fixtures.PROFILE_ID, mode),
                null,
                Duration.ofMinutes(40),
                Fixtures.NOW);
        request.startCollecting(Fixtures.NOW.plusSeconds(1));
        request.corpusCollected(Fixtures.SNAPSHOT_ID, Fixtures.corpusCoverage(), Fixtures.NOW.plusSeconds(2));
        request.startAssembling(UUID.randomUUID(), Fixtures.NOW.plusSeconds(3));
        return request;
    }

    @Test
    void aQualityReportIsLabelledQualityAndSaysSoOnTheWire() {
        var request = assembling(AnalysisMode.QUALITY);
        var result = Fixtures.analysisResult(
                request, List.of(Fixtures.analyzedTrend(1, "a"), Fixtures.analyzedTrend(2, "b")));

        var report = new ReportAssembler()
                .assemble(request, result, List.of("arxiv"), List.of(), 1, null, Fixtures.NOW.plusSeconds(4));

        assertThat(report.methodology().mode()).isEqualTo("quality");
        assertThat(views.toView(report, Map.of(), List.of()).mode()).isEqualTo("quality");
    }

    @Test
    void aReportWrittenBeforeModesIsFast() {
        // Строка отчёта до V21 и снимок в кэше до этой правки режима не несут; считались они в срок
        // быстрого.
        var legacy = new MethodologyRef("em-1.0.0", Fixtures.PROFILE_ID, "WEIGHTED_GEOMETRIC", "methodology", null);

        assertThat(legacy.mode()).isEqualTo("fast");
        assertThat(legacy.engine()).isEqualTo("methodology");
    }
}
