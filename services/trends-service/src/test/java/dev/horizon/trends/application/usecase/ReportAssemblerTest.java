package dev.horizon.trends.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.horizon.platform.common.error.HorizonException;
import dev.horizon.platform.common.error.ProblemType;
import dev.horizon.trends.application.dto.AnalysisResult;
import dev.horizon.trends.domain.report.RankedTrend;
import dev.horizon.trends.support.Fixtures;

/**
 * The anti-corruption boundary between the Python engine and this bounded context (ADR-0016).
 *
 * <p>Its behaviour under bad input is a product decision, not a technical one: a single malformed
 * trend must cost the analyst that trend, not the other fourteen. The assembler therefore drops
 * what it cannot publish, renumbers the survivors so the report is still well-formed, and fails
 * only when nothing publishable is left.
 */
class ReportAssemblerTest {

    private final ReportAssembler assembler = new ReportAssembler();

    private static AnalysisResult result(List<AnalysisResult.AnalyzedTrend> trends) {
        return Fixtures.analysisResult(Fixtures.assemblingRequest(), trends);
    }

    private static List<AnalysisResult.AnalyzedTrend> analyzed(int count) {
        List<AnalysisResult.AnalyzedTrend> trends = new ArrayList<>(count);
        for (int rank = 1; rank <= count; rank++) {
            trends.add(Fixtures.analyzedTrend(rank, "trend-" + rank));
        }
        return trends;
    }

    private static AnalysisResult.AnalyzedTrend withoutEvidence(AnalysisResult.AnalyzedTrend source) {
        return new AnalysisResult.AnalyzedTrend(
                source.rank(),
                source.rankStability(),
                source.directionShare(),
                source.trendKey(),
                source.title(),
                source.definition(),
                source.aliases(),
                source.motivation(),
                null,
                source.score(),
                source.confidence(),
                source.lowEvidence(),
                source.indicators(),
                source.lifecycleStage(),
                source.firstMentionYear(),
                source.totalDocuments(),
                source.burst(),
                source.timeline(),
                List.of());
    }

    private static AnalysisResult.AnalyzedTrend withUnknownStage(AnalysisResult.AnalyzedTrend source) {
        return new AnalysisResult.AnalyzedTrend(
                source.rank(),
                source.rankStability(),
                source.directionShare(),
                source.trendKey(),
                source.title(),
                source.definition(),
                source.aliases(),
                source.motivation(),
                source.caseExample(),
                source.score(),
                source.confidence(),
                source.lowEvidence(),
                source.indicators(),
                "SOMETHING_NEW",
                source.firstMentionYear(),
                source.totalDocuments(),
                source.burst(),
                source.timeline(),
                source.evidence());
    }

    @Test
    @DisplayName("maps a well-formed result into a report without losing anything")
    void mapsCleanResult() {
        var request = Fixtures.assemblingRequest();

        var report =
                assembler.assemble(request, result(analyzed(5)), List.of("arxiv"), List.of(), 1, null, Fixtures.NOW);

        assertThat(report.trends()).hasSize(5);
        assertThat(report.trends()).extracting(RankedTrend::rank).containsExactly(1, 2, 3, 4, 5);
        assertThat(report.methodology().version()).isEqualTo("em-1.0.0");
        assertThat(report.corpusSnapshotId()).isEqualTo(Fixtures.SNAPSHOT_ID);
        assertThat(report.coverage().sourcesUsed()).containsExactly("arxiv");
    }

    @Test
    @DisplayName("drops a trend that has no evidence and renumbers the rest (BR-A6, J1)")
    void dropsUnpublishableTrendAndRenumbers() {
        var trends = new ArrayList<>(analyzed(5));
        trends.set(2, withoutEvidence(trends.get(2)));

        var report = assembler.assemble(
                Fixtures.assemblingRequest(), result(trends), List.of("arxiv"), List.of(), 1, null, Fixtures.NOW);

        assertThat(report.trends()).hasSize(4);
        // Renumbering matters: a report with ranks 1,2,4,5 would violate J1 and would read to a
        // user as though a trend had been hidden from them.
        assertThat(report.trends()).extracting(RankedTrend::rank).containsExactly(1, 2, 3, 4);
        assertThat(report.trends()).extracting(RankedTrend::trendKey).doesNotContain("trend-3");
        assertThat(report.truncated()).isTrue();
    }

    @Test
    @DisplayName("drops a trend with an unknown enum value rather than failing the whole report")
    void toleratesUnknownEnumValue() {
        // Forward compatibility in the wrong direction: the engine may start emitting a lifecycle
        // stage this version does not know. Losing that one trend is acceptable; losing the report
        // is not.
        var trends = new ArrayList<>(analyzed(3));
        trends.set(0, withUnknownStage(trends.get(0)));

        var report = assembler.assemble(
                Fixtures.assemblingRequest(), result(trends), List.of("arxiv"), List.of(), 1, null, Fixtures.NOW);

        assertThat(report.trends()).hasSize(2);
        assertThat(report.trends()).extracting(RankedTrend::rank).containsExactly(1, 2);
    }

    @Test
    @DisplayName("never returns more trends than the request asked for")
    void respectsTopN() {
        var request = Fixtures.assemblingRequest();
        int topN = request.parameters().topN();

        var report = assembler.assemble(
                request, result(analyzed(topN + 7)), List.of("arxiv"), List.of(), 1, null, Fixtures.NOW);

        assertThat(report.trends()).hasSize(topN);
    }

    @Test
    @DisplayName("fails explicitly when nothing publishable survives")
    void failsWhenEverythingIsDropped() {
        var trends =
                analyzed(3).stream().map(ReportAssemblerTest::withoutEvidence).toList();

        assertThatThrownBy(() -> assembler.assemble(
                        Fixtures.assemblingRequest(),
                        result(trends),
                        List.of("arxiv"),
                        List.of(),
                        1,
                        null,
                        Fixtures.NOW))
                .isInstanceOfSatisfying(HorizonException.class, error -> assertThat(error.type())
                        .isEqualTo(ProblemType.ANALYSIS_FAILED));
    }

    @Test
    @DisplayName("fails when the engine returned no trends at all")
    void failsOnEmptyResult() {
        assertThatThrownBy(() -> assembler.assemble(
                        Fixtures.assemblingRequest(),
                        result(List.of()),
                        List.of("arxiv"),
                        List.of(),
                        1,
                        null,
                        Fixtures.NOW))
                .isInstanceOf(HorizonException.class);
    }

    @Test
    @DisplayName("an unavailable source makes the report partial")
    void unavailableSourceMarksReportPartial() {
        var report = assembler.assemble(
                Fixtures.assemblingRequest(),
                result(analyzed(2)),
                List.of("arxiv"),
                List.of("uspto"),
                1,
                null,
                Fixtures.NOW);

        assertThat(report.coverage().partial()).isTrue();
        assertThat(report.coverage().unavailableSources()).containsExactly("uspto");
    }

    @Test
    @DisplayName("ignores the order the engine sent trends in and trusts only the rank")
    void sortsByRank() {
        var shuffled = new ArrayList<>(analyzed(4));
        java.util.Collections.shuffle(shuffled, new java.util.Random(7));

        var report = assembler.assemble(
                Fixtures.assemblingRequest(), result(shuffled), List.of("arxiv"), List.of(), 1, null, Fixtures.NOW);

        assertThat(report.trends())
                .extracting(RankedTrend::trendKey)
                .containsExactly("trend-1", "trend-2", "trend-3", "trend-4");
    }

    @Test
    void theCorpusLimitFromTheEngineReachesTheReport() {
        // Проводка, а не формулировка. Оговорка «корпус обрезан пределом» приходила от движка и
        // терялась: сборщик её не передавал, а поле с тем же именем в отчёте вычислялось заново и
        // означало другое — «тем найдено меньше запрошенных». Проверки самой записки этого не
        // видели: они строят покрытие напрямую, минуя сборщик. Подмена «снова терять флаг» проходила
        // незамеченной, пока не появилась эта проверка.
        var fromEngine = Fixtures.analysisResultTruncated(Fixtures.assemblingRequest(), analyzed(5));

        var report = assembler.assemble(
                Fixtures.assemblingRequest(), fromEngine, List.of("arxiv"), List.of(), 1, null, Fixtures.NOW);

        assertThat(report.coverage().corpusTruncated()).isTrue();
    }

    @Test
    void aCompleteCorpusIsReportedAsComplete() {
        // Обратная сторона: флаг, выставленный всегда, тоже прошёл бы проверку выше.
        var report = assembler.assemble(
                Fixtures.assemblingRequest(), result(analyzed(5)), List.of("arxiv"), List.of(), 1, null, Fixtures.NOW);

        assertThat(report.coverage().corpusTruncated()).isFalse();
    }
}
