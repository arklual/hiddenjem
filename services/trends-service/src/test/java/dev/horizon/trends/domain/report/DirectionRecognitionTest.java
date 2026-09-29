package dev.horizon.trends.domain.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.horizon.trends.application.dto.AnalysisResult;

/**
 * Оговорка «направление не распознано» и её отличие от прочих.
 *
 * <p>{@code partial} и {@code unavailableSources} говорят «мы нашли меньше, чем хотели». Это поле
 * говорит другое: <b>мы не поняли вопроса</b>. Движок не смог соотнести запрошенное направление со
 * словарём предметных кодов, которым размечен корпус, и его выбор того, что относится к
 * направлению, не несёт информации — при том что отчёт полон, посчитан и правдоподобен.
 *
 * <p>Цена этих двух ошибок разная, и в этом всё дело. Принять пустой ответ за «в направлении ничего
 * не происходит» стоит аналитику переформулировки. Принять этот отчёт за ответ стоит записки в
 * комитет.
 */
class DirectionRecognitionTest {

    private static Coverage coverage(boolean directionRecognized) {
        return coverage(directionRecognized, List.of("квантовые вычисления"));
    }

    private static Coverage coverage(boolean directionRecognized, List<String> suggestions) {
        return new Coverage(
                120,
                340,
                List.of("arxiv", "openalex"),
                List.of(),
                false,
                directionRecognized,
                suggestions,
                0,
                false,
                LocalDate.of(2019, 3, 1),
                LocalDate.of(2026, 3, 1));
    }

    @Test
    @DisplayName("нераспознанное направление не делает покрытие неполным")
    void anUnrecognisedDirectionIsNotTheSameAsPartialCoverage() {
        // Две разные оговорки об одном отчёте, и смешивать их нельзя: источники были доступны и
        // корпус собран полностью. Неверно понят был вопрос, а не данные.
        Coverage coverage = coverage(false);

        assertThat(coverage.directionRecognized()).isFalse();
        assertThat(coverage.partial()).isFalse();
        assertThat(coverage.unavailableSources()).isEmpty();
    }

    @Test
    @DisplayName("оговорка доходит до портрета направления")
    void theCaveatReachesTheDirectionPortrait() {
        // Портрет — то, из чего собираются обе выгрузки. Потеряться оговорке здесь значит уйти по
        // почте в виде обычного отчёта.
        assertThat(portraitOf(coverage(false)).directionRecognized()).isFalse();
        assertThat(portraitOf(coverage(true)).directionRecognized()).isTrue();
    }

    @Test
    @DisplayName("отсутствие поля в событии читается как «направление распознано»")
    void anAbsentFieldReadsAsRecognised() {
        // Поле аддитивное: событие, выпущенное до его появления, его не несёт. Примитивный boolean
        // прочитал бы отсутствие как false и объявил бы нераспознанными все прежние отчёты.
        // Оговорка, срабатывающая не по делу, обесценивает те, где она по делу.
        assertThat(resultWith(null).directionRecognizedOrTrue()).isTrue();
    }

    @Test
    @DisplayName("явное «не распознано» переживает границу с движком")
    void anExplicitFalseSurvivesTheEngineBoundary() {
        assertThat(resultWith(Boolean.FALSE).directionRecognizedOrTrue()).isFalse();
        assertThat(resultWith(Boolean.TRUE).directionRecognizedOrTrue()).isTrue();
    }

    @Test
    @DisplayName("подсказка живёт только вместе с оговоркой")
    void suggestionsExistOnlyAlongsideTheCaveat() {
        // Предложить переформулировать удавшийся запрос — совет, который читается как «что-то не
        // так», хотя всё так. Оговорка и подсказка — одно утверждение, и разъехаться они не могут.
        assertThat(coverage(false).directionSuggestions()).containsExactly("квантовые вычисления");
        assertThat(coverage(true).directionSuggestions()).isEmpty();
    }

    @Test
    @DisplayName("отсутствие подсказок не роняет сборку отчёта")
    void missingSuggestionsAreNotFatal() {
        // Движок вправе не найти ни одной близкой формулировки: подсказка наугад хуже её
        // отсутствия. Плашка тогда показывает общий совет вместо ссылок.
        assertThat(coverage(false, null).directionSuggestions()).isEmpty();
        assertThat(resultWith(Boolean.FALSE).directionSuggestionsOrEmpty()).isEmpty();
    }

    private static DirectionPortrait portraitOf(Coverage coverage) {
        return DirectionPortrait.of(dev.horizon.trends.support.Fixtures.reportWithCoverage(
                dev.horizon.trends.support.Fixtures.assemblingRequest(), coverage));
    }

    private static AnalysisResult resultWith(Boolean directionRecognized) {
        return new AnalysisResult(
                "00000000-0000-4000-8000-000000000000",
                1,
                "00000000-0000-4000-8000-000000000001",
                "em-1.0.0",
                "00000000-0000-4000-8000-000000000002",
                "WEIGHTED_GEOMETRIC",
                "tfidf-svd-384-v1",
                120,
                340,
                false,
                0,
                directionRecognized,
                null,
                LocalDate.of(2019, 3, 1),
                LocalDate.of(2026, 3, 1),
                java.util.Map.of(),
                14,
                List.of());
    }
}
