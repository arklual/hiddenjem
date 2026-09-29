package dev.horizon.trends.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.horizon.trends.application.dto.AnalysisResult;
import dev.horizon.trends.support.Fixtures;

/**
 * Диапазон места доезжает от движка до отчёта — и не выдумывается, когда его не присылали.
 *
 * <p>Веса шести индикаторов выбраны экспертно (методология §16). Диапазон «тема занимает с 3-го по
 * 11-е место при разумных изменениях весов» — единственное, что отличает вывод о направлении от
 * вывода о настройках. Он считается движком: веса и агрегатор принадлежат методологии, и вторая их
 * копия на стороне отчёта разошлась бы с первой молча.
 *
 * <p>Вторая половина проверки не менее важна первой. Отчёты, выпущенные до появления §16, диапазона
 * не несут, и подставить им нынешнее место значило бы объявить каждую старую тему идеально
 * устойчивой — утверждение, которого никто не проверял. Ложная оговорка о надёжности хуже её
 * отсутствия: она обесценивает настоящие.
 */
class RankStabilityCrossesTheBoundaryTest {

    private final ReportAssembler assembler = new ReportAssembler();

    private static AnalysisResult.AnalyzedTrend withStability(
            int rank, String key, AnalysisResult.RankStabilityDto stability) {
        AnalysisResult.AnalyzedTrend source = Fixtures.analyzedTrend(rank, key);
        return new AnalysisResult.AnalyzedTrend(
                source.rank(),
                stability,
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
                source.lifecycleStage(),
                source.firstMentionYear(),
                source.totalDocuments(),
                source.burst(),
                source.timeline(),
                source.evidence());
    }

    /** Тема без источников: домен не публикует такую (BR-A6), и сборщик её выбрасывает. */
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

    private static List<dev.horizon.trends.domain.report.RankedTrend> assemble(
            ReportAssembler assembler, AnalysisResult.AnalyzedTrend... trends) {
        var request = Fixtures.assemblingRequest();
        return assembler
                .assemble(
                        request,
                        Fixtures.analysisResult(request, List.of(trends)),
                        List.of("arxiv"),
                        List.of(),
                        1,
                        null,
                        Fixtures.NOW)
                .trends();
    }

    @Test
    @DisplayName("диапазон места доходит до отчёта таким, каким его прислал движок")
    void carriesTheRangeTheEngineMeasured() {
        var trend = withStability(1, "selective-state-space", new AnalysisResult.RankStabilityDto(2, 11));

        var assessment = assemble(assembler, trend).getFirst().assessment();

        assertThat(assessment.rankStabilityOptional()).isPresent();
        assertThat(assessment.rankStability().best()).isEqualTo(2);
        assertThat(assessment.rankStability().worst()).isEqualTo(11);
    }

    @Test
    @DisplayName("отчёт без измерения не объявляется устойчивым")
    void doesNotInventARangeForAReportThatCarriesNone() {
        var trend = withStability(1, "старый-отчёт", null);

        var assessment = assemble(assembler, trend).getFirst().assessment();

        assertThat(assessment.rankStabilityOptional()).isEmpty();
    }

    @Test
    @DisplayName("неподвижная тема отличается от той, что просто не двигалась далеко")
    void tellsAnImmovableTopicApartFromANearlyImmovableOne() {
        // Разница в одно место, но говорят они разное: «первое место не зависит от весов вовсе» и
        // «первое место держится, пока веса примерно такие». Второе слабее, и продавать его как
        // первое означало бы ту же подмену, ради устранения которой всё измерение и делается.
        var immovable = assemble(assembler, withStability(1, "неподвижная", new AnalysisResult.RankStabilityDto(1, 1)))
                .getFirst()
                .assessment()
                .rankStability();
        var nearly = assemble(assembler, withStability(1, "почти", new AnalysisResult.RankStabilityDto(1, 2)))
                .getFirst()
                .assessment()
                .rankStability();

        assertThat(immovable.immovable()).isTrue();
        assertThat(nearly.immovable()).isFalse();
        assertThat(nearly.holdsInTop(2)).isTrue();
        assertThat(nearly.holdsInTop(1)).isFalse();
    }

    @Test
    @DisplayName("диапазон не противоречит месту, показанному рядом с ним")
    void neverContradictsTheRankPrintedNextToIt() {
        // Сборщик выбрасывает непубликуемую тему и перенумеровывает остальные, а диапазон приходит
        // от движка в прежней нумерации. Тогда карточка говорит «место 2» и тут же «при других
        // весах — с 3-го по 3-е»: отчёт противоречит сам себе в двух соседних строках, и читатель
        // вправе не поверить обеим. BR-A35 запрещает именно это.
        var good = withStability(1, "первая", new AnalysisResult.RankStabilityDto(1, 1));
        var broken = withoutEvidence(withStability(2, "битая", new AnalysisResult.RankStabilityDto(2, 2)));
        var shifted = withStability(3, "сдвинутая", new AnalysisResult.RankStabilityDto(3, 3));

        var trends = assemble(assembler, good, broken, shifted);

        assertThat(trends).hasSize(2);
        // Теряет измерение только та тема, чьё место сдвинулось. Выбрасывать диапазоны у всех тем
        // из-за одной битой было бы дороже, чем нужно: выброс обычно один и в середине списка.
        assertThat(trends.get(0).assessment().rankStabilityOptional())
                .as("несдвинувшаяся тема сохраняет измерение")
                .isPresent();
        assertThat(trends.get(1).assessment().rankStabilityOptional())
                .as("сдвинувшаяся тема измерение теряет: оно относится к другому порядку")
                .isEmpty();
        for (var trend : trends) {
            var range = trend.assessment().rankStabilityOptional();
            if (range.isPresent()) {
                assertThat(trend.rank())
                        .as("место темы «%s» вне её же диапазона", trend.trendKey())
                        .isBetween(range.get().best(), range.get().worst());
            }
        }
    }

    @Test
    @DisplayName("число перебранных наборов весов доходит до отчёта")
    void carriesHowManyWeightingsWereTried() {
        // Ноль означает «не измеряли», а не «неустойчиво». Без этого числа диапазон нечем
        // истолковать: «с 2-го по 11-е» из двух наборов и из четырнадцати — разные утверждения.
        var result = Fixtures.analysisResult(Fixtures.assemblingRequest(), List.of(Fixtures.analyzedTrend(1, "тема")));

        assertThat(result.reweightingScenariosOrZero()).isEqualTo(14);
    }
}
