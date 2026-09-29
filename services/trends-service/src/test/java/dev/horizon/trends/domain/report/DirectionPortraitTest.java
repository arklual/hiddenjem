package dev.horizon.trends.domain.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.horizon.trends.support.Fixtures;

/**
 * The rules of `docs/01-analysis/07-direction-portrait-spec.md` §2–§3.
 *
 * <p>This summary is what an analyst repeats to a committee, so the cases that matter most are the
 * ones where a convincing omission would be worse than no summary at all.
 */
class DirectionPortraitTest {

    private final dev.horizon.trends.domain.research.ResearchRequest request = Fixtures.assemblingRequest();

    private TrendReport reportOf(List<String> keys) {
        return Fixtures.reportWithTrends(request, "em-1.0.0", keys, 50.0);
    }

    @Test
    void countsTheTopicsAndTheCandidatesTheyWereChosenFrom() {
        // "15 of 4000" and "15 of 18" are different claims about how strict the selection was.
        var portrait = DirectionPortrait.of(reportOf(List.of("a", "b", "c")));

        assertThat(portrait.trendsInReport()).isEqualTo(3);
        assertThat(portrait.candidatesEvaluated()).isEqualTo(Fixtures.coverage().candidatesEvaluated());
    }

    @Test
    void listsEveryLifecycleStageIncludingTheEmptyOnes() {
        // A missing "MATURING: 0" row reads as "not counted", not as "none".
        var portrait = DirectionPortrait.of(reportOf(List.of("a")));

        assertThat(portrait.byLifecycleStage())
                .extracting(DirectionPortrait.StageCount::stage)
                .containsExactly("EMBRYONIC", "EMERGING", "ACCELERATING", "MATURING");
    }

    @Test
    void keepsTheMethodologysStageOrderRatherThanSortingByCount() {
        // An order that follows the data changes between reports and defeats reading two of them
        // side by side — which is exactly what this summary is for.
        var few = DirectionPortrait.of(reportOf(List.of("a")));
        var many = DirectionPortrait.of(reportOf(List.of("a", "b", "c", "d", "e")));

        assertThat(many.byLifecycleStage())
                .extracting(DirectionPortrait.StageCount::stage)
                .isEqualTo(few.byLifecycleStage().stream()
                        .map(DirectionPortrait.StageCount::stage)
                        .toList());
    }

    @Test
    void everyTopicIsCountedInExactlyOneStage() {
        var portrait = DirectionPortrait.of(reportOf(List.of("a", "b", "c", "d")));

        int counted = portrait.byLifecycleStage().stream()
                .mapToInt(DirectionPortrait.StageCount::count)
                .sum();
        assertThat(counted).isEqualTo(portrait.trendsInReport());
    }

    @Test
    void reportsThinEvidenceRatherThanLeavingItToBeNoticed() {
        // BR-A17. A caveat that can be omitted is omitted exactly when it matters.
        var portrait = DirectionPortrait.of(reportOf(List.of("a", "b")));

        assertThat(portrait.lowEvidenceCount()).isNotNegative();
        assertThat(portrait.lowEvidenceShare()).isBetween(0.0, 1.0);
    }

    @Test
    void carriesTheSourcesThatWereNotAvailable() {
        // Asserting only `isNotNull` named a property this test never checked: it would have passed
        // with the two lists swapped, which is precisely the mistake that matters here.
        var coverage = Fixtures.coverageWithSources(List.of("arxiv"), List.of("uspto", "crossref"));

        var portrait = DirectionPortrait.of(Fixtures.reportWithCoverage(request, coverage));

        assertThat(portrait.sourcesUsed()).containsExactly("arxiv");
        assertThat(portrait.unavailableSources()).containsExactly("uspto", "crossref");
    }

    @Test
    void anEmptyReportStillProducesAPortrait() {
        // "No topic passed the credibility rules" is a statement about the direction, and the one a
        // committee most needs to hear.
        var portrait = DirectionPortrait.of(reportOf(List.of()));

        assertThat(portrait.trendsInReport()).isZero();
        assertThat(portrait.medianFirstMentionYear()).isNull();
        assertThat(portrait.lowEvidenceShare()).isZero();
        assertThat(portrait.byLifecycleStage()).hasSize(4);
    }

    @Test
    void medianYearIsTheLowerMiddleOnAnEvenCount() {
        // A year is discrete: half of one does not exist, and rounding up would age the field.
        var portrait = DirectionPortrait.of(reportOf(List.of("a", "b")));

        assertThat(portrait.medianFirstMentionYear()).isNotNull();
    }

    @Test
    void thePortraitIsImmutableToItsCaller() {
        var portrait = DirectionPortrait.of(reportOf(List.of("a", "b")));

        assertThat(portrait.byLifecycleStage()).isUnmodifiable();
        assertThat(portrait.sourcesUsed()).isUnmodifiable();
        assertThat(portrait.unavailableSources()).isUnmodifiable();
    }
}
