package dev.horizon.trends.domain.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Monotonicity and clamping of {@link AnalysisProgress} (invariant I5). */
class AnalysisProgressTest {

    private static final Instant AT = Instant.parse("2026-03-01T10:00:00Z");

    @Test
    void queuedStartsAtZero() {
        var progress = AnalysisProgress.queued(AT);

        assertThat(progress.stage()).isEqualTo(AnalysisStage.QUEUED);
        assertThat(progress.percent()).isZero();
    }

    @Test
    void advancingKeepsTheMaximumPercentEvenWhenTheStageMovesOn() {
        var progress = AnalysisProgress.queued(AT)
                .advanceTo(AnalysisStage.ANALYZING, 60, "шестьдесят", AT.plusSeconds(10))
                .advanceTo(AnalysisStage.ANALYZING, 20, "запоздавшее двадцать", AT.plusSeconds(11));

        assertThat(progress.percent()).isEqualTo(60);
        // The reported stage and message still follow the latest event; only the bar is monotonic.
        assertThat(progress.message()).isEqualTo("запоздавшее двадцать");
        assertThat(progress.updatedAt()).isEqualTo(AT.plusSeconds(11));
    }

    @Test
    void completedJumpsToOneHundred() {
        var progress = AnalysisProgress.queued(AT).completed(AT.plusSeconds(90));

        assertThat(progress.stage()).isEqualTo(AnalysisStage.DONE);
        assertThat(progress.percent()).isEqualTo(100);
    }

    @Test
    void anOverlongMessageIsTruncatedToTheColumnWidth() {
        var progress = new AnalysisProgress(AnalysisStage.ANALYZING, 10, "я".repeat(500), AT);

        assertThat(progress.message()).hasSize(300);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 101})
    void percentOutsideZeroToHundredIsRejected(int percent) {
        assertThatThrownBy(() -> new AnalysisProgress(AnalysisStage.ANALYZING, percent, "x", AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "{0} внутри стадии → {1} на общей шкале")
    @CsvSource({"0, 40", "50, 63", "100, 85"})
    void withinStagePercentIsMappedOntoTheGlobalBar(int withinStage, int expectedGlobal) {
        assertThat(AnalysisStage.ANALYZING.globalPercent(withinStage)).isEqualTo(expectedGlobal);
    }

    @Test
    void anOutOfRangeStagePercentIsClampedRatherThanRejected() {
        // Progress is advisory and produced by another service; a bad number must not fail the saga.
        assertThat(AnalysisStage.ANALYZING.globalPercent(-50)).isEqualTo(40);
        assertThat(AnalysisStage.ANALYZING.globalPercent(500)).isEqualTo(85);
    }

    @Test
    void everyStatusMapsToAStage() {
        for (ResearchStatus status : ResearchStatus.values()) {
            assertThat(AnalysisStage.forStatus(status)).isNotNull();
        }
        assertThat(AnalysisStage.forStatus(ResearchStatus.CANCELLED)).isEqualTo(AnalysisStage.DONE);
    }
}
