package dev.horizon.trends.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import dev.horizon.trends.config.FeatureFlags;
import dev.horizon.trends.config.FeatureGate;
import dev.horizon.trends.domain.report.CaseExample;
import dev.horizon.trends.domain.report.EmergenceAssessment;
import dev.horizon.trends.domain.report.Evidence;
import dev.horizon.trends.domain.report.LifecycleStage;
import dev.horizon.trends.domain.report.Motivation;
import dev.horizon.trends.domain.report.RankStability;
import dev.horizon.trends.domain.report.RankedTrend;
import dev.horizon.trends.domain.report.SourceClass;
import dev.horizon.trends.domain.report.StabilitySummary;
import dev.horizon.trends.domain.report.TimelinePoint;
import dev.horizon.trends.domain.report.TrendReport;
import dev.horizon.trends.support.Fixtures;

/**
 * Диапазон места доезжает до тех, кто экрана не видел.
 *
 * <p>Записку читает комитет, и вопрос «а если бы веса выбрали иначе — список был бы тем же?» задают
 * именно там. Измерение, доступное только на экране аналитика, отвечает не тому, кто спрашивает.
 * Ровно этой ошибкой — «половина работы хуже её отсутствия» — продукт уже болел в обратную сторону:
 * оговорка о неполноте корпуса доезжала до выгрузки и не доезжала до экрана.
 *
 * <p>Вторая половина проверок про молчание. Отчёты, выпущенные до появления §16, диапазона не
 * несут, и доля «0 из 15 устойчивы» была бы для них неправдой — самой опасной из возможных, потому
 * что выглядит она как измерение.
 */
class StabilityReachesTheCommitteeTest {

    private final ExportReportUseCase export =
            new ExportReportUseCase(new FeatureGate(new FeatureFlags(new MockEnvironment())));
    private final dev.horizon.trends.domain.research.ResearchRequest request = Fixtures.assemblingRequest();

    private static RankedTrend trend(int rank, String key, RankStability stability) {
        return new RankedTrend(
                rank,
                key,
                "Тема " + key,
                "Определение",
                new Motivation("Проблема", "Польза", List.of(new Motivation.Attribution("problem", 0, "Проблема"))),
                new CaseExample(
                        "МФТИ",
                        CaseExample.OrganizationType.UNIVERSITY,
                        "RU",
                        "Прототип",
                        0,
                        CaseExample.Basis.ACADEMIC_GROUP),
                new EmergenceAssessment(72.5, 0.81, false, Fixtures.indicators(), stability),
                LifecycleStage.EMERGING,
                2022,
                57,
                null,
                List.of(new TimelinePoint("2024", 31, 0.7, 0.5)),
                List.of(new Evidence(
                        "arxiv",
                        SourceClass.PREPRINT,
                        "2403.0001",
                        "Статья",
                        "Иванов И.И.",
                        "МФТИ",
                        "RU",
                        LocalDate.of(2025, 6, 1),
                        "https://arxiv.org/abs/2403.0001",
                        "10.1/abc",
                        42,
                        0.87,
                        "фрагмент")));
    }

    private TrendReport reportOf(List<RankedTrend> trends) {
        return TrendReport.create(
                request.id(),
                Fixtures.requester(),
                request.query(),
                Fixtures.methodology(),
                Fixtures.SNAPSHOT_ID,
                Fixtures.coverage(),
                trends,
                trends.size(),
                1,
                null,
                Fixtures.NOW.plusSeconds(90));
    }

    /** Отчёт из трёх тем: одна неподвижная, одна плавающая внутри отчёта, одна выпадающая из него. */
    private TrendReport mixedReport() {
        return reportOf(List.of(
                trend(1, "неподвижная", new RankStability(1, 1)),
                trend(2, "плавающая", new RankStability(2, 3)),
                trend(3, "выпадающая", new RankStability(2, 9))));
    }

    @Test
    @DisplayName("свод считает устойчивой тему по её худшему случаю, а не по обычному")
    void countsATopicSteadyOnlyIfItsWorstCaseKeepsItInTheReport() {
        var summary = StabilitySummary.of(mixedReport().trends());

        assertThat(summary.total()).isEqualTo(3);
        assertThat(summary.measured()).isEqualTo(3);
        assertThat(summary.steady()).isEqualTo(2);
        assertThat(summary.weightDependent()).isEqualTo(1);
    }

    @Test
    @DisplayName("свод не выдаёт неизмеренный отчёт за полностью неустойчивый")
    void doesNotReportAnUnmeasuredReportAsUnstable() {
        // «0 из 15 устойчивы» и «устойчивость не измерялась» — разные утверждения, и первое
        // выглядит как результат проверки, которой не было.
        var summary =
                StabilitySummary.of(reportOf(List.of(trend(1, "старая", null))).trends());

        assertThat(summary.known()).isFalse();
        assertThat(summary.measured()).isZero();
    }

    @Test
    @DisplayName("записка отвечает комитету одной строкой, а не таблицей")
    void theBriefingAnswersTheCommitteeQuestionInTheCaveats() {
        String briefing = export.toMarkdown(mixedReport());

        assertThat(briefing).contains("Устойчивость к весам: 2 из 3");
        assertThat(briefing).contains("присутствие остальных 1 зависит от выбранного взвешивания");
        // Оговорка обязана стоять до тем: прочитанная после трёх обоснованных карточек, она
        // прочитана после решения.
        assertThat(briefing.indexOf("Устойчивость к весам")).isLessThan(briefing.indexOf("## Темы"));
    }

    @Test
    @DisplayName("записка называет диапазон у каждой темы и отличает неподвижную")
    void theBriefingNamesTheRangePerTopic() {
        String briefing = export.toMarkdown(mixedReport());

        assertThat(briefing).contains("- Место: 1 — не меняется ни при одном из рассмотренных наборов весов");
        assertThat(briefing).contains("- Место: 3, при других весах — с 2-го по 9-е");
    }

    @Test
    @DisplayName("записка молчит о том, чего не измеряли")
    void theBriefingSaysNothingWhenNothingWasMeasured() {
        String briefing = export.toMarkdown(reportOf(List.of(trend(1, "старая", null))));

        assertThat(briefing).doesNotContain("Устойчивость к весам");
        assertThat(briefing).doesNotContain("- Место:");
    }

    @Test
    @DisplayName("таблица несёт диапазон рядом с местом, а не в конце строки")
    void theTableCarriesTheRangeNextToTheRank() {
        String csv = export.toCsv(mixedReport());
        String header =
                csv.lines().filter(line -> line.startsWith("rank;")).findFirst().orElseThrow();

        // Рядом с местом теперь стоит и доля направления: обе величины отвечают на один вопрос —
        // чем является место темы, — и разносить их по разным концам строки значило бы заставить
        // читателя сводить их глазами.
        assertThat(header).startsWith("rank;rank_stability_best;rank_stability_worst;direction_share;trend;");
        assertThat(csv).contains("\n3;2;9;;");
    }

    @Test
    @DisplayName("в таблице неизмеренная тема оставляет ячейки пустыми, а не нулевыми")
    void theTableLeavesUnmeasuredCellsEmptyRatherThanZero() {
        // Ноль означал бы нулевое место, которого не бывает, и скрипт принял бы его за измерение.
        String csv = export.toCsv(reportOf(List.of(trend(1, "старая", null))));

        assertThat(csv).contains("\n1;;;;");
        assertThat(csv).contains("тем с измеренной устойчивостью;0");
    }
}
