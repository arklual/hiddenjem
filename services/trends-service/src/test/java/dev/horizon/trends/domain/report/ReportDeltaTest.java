package dev.horizon.trends.domain.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import dev.horizon.trends.domain.research.ResearchRequest;
import dev.horizon.trends.domain.research.TechnologyDomainQuery;
import dev.horizon.trends.support.Fixtures;

/**
 * Comparison of two report versions — the edge cases of `docs/01-analysis/06-trend-delta-spec.md` §6.
 *
 * <p>The delta is what an analyst reads on the second and every later run, so a wrong answer here is
 * not a cosmetic defect: it sends attention to the wrong topics while looking authoritative.
 */
class ReportDeltaTest {

    /**
     * One request, shared by every version built in a test.
     *
     * <p>Convenience only. Two versions of a direction belong to <em>different</em> requests — every
     * submission creates a new one — and the delta compares by direction. It used to compare by
     * request, which meant it refused every real pair and answered "nothing to compare" under every
     * possible state of the system; this docstring asserted that could not happen.
     */
    private final ResearchRequest request = Fixtures.assemblingRequest();

    /** A version of some other direction — a different question, not a different run. */
    private static TrendReport reportOfAnotherDirection() {
        var other = ResearchRequest.submit(
                Fixtures.requester(),
                TechnologyDomainQuery.of("биотехнологии в медицине"),
                Fixtures.parameters(),
                null,
                java.time.Duration.ofMinutes(10),
                Fixtures.NOW);
        other.startCollecting(Fixtures.NOW.plusSeconds(1));
        other.corpusCollected(Fixtures.SNAPSHOT_ID, Fixtures.corpusCoverage(), Fixtures.NOW.plusSeconds(2));
        other.startAssembling(java.util.UUID.randomUUID(), Fixtures.NOW.plusSeconds(3));
        return Fixtures.reportWithTrends(other, "em-1.0.0", List.of("a"), 50.0);
    }

    private TrendReport reportWith(String methodologyVersion, List<String> keysInRankOrder, double baseScore) {
        return Fixtures.reportWithTrends(request, methodologyVersion, keysInRankOrder, baseScore);
    }

    private TrendReport reportWith(List<String> keysInRankOrder) {
        return reportWith("em-1.0.0", keysInRankOrder, 50.0);
    }

    /** Второй профиль: те же формулы, другие веса. */
    private static final java.util.UUID OTHER_PROFILE =
            java.util.UUID.fromString("44444444-4444-4444-8444-444444444444");

    /**
     * Отчёт с заданной методологией целиком — версия, профиль и агрегатор.
     *
     * <p>Прежние помощники меняли только версию, и проверить остальные две трети линейки было
     * нечем.
     */
    private TrendReport reportWithMethodology(MethodologyRef methodology) {
        var source = reportWith(List.of("a"));
        return TrendReport.create(
                source.researchRequestId(),
                Fixtures.requester(),
                source.query(),
                methodology,
                Fixtures.SNAPSHOT_ID,
                source.coverage(),
                source.trends(),
                source.trends().size(),
                source.version(),
                source.previousVersionId().orElse(null),
                source.generatedAt());
    }

    @Test
    void reportsOfDifferentDirectionsAreNotCompared() {
        // Two directions are two questions: their top lists share no subject and their scores no
        // scale, so a difference between them would be a number with no meaning.
        var delta = ReportDelta.between(reportWith(List.of("a")), reportOfAnotherDirection());

        assertThat(delta.isAvailable()).isFalse();
    }

    @Test
    void twoRunsOfTheSameDirectionAreCompared() {
        // The pair the feature exists for. Every submission creates a new request, so requiring the
        // requests to match refused this — and the delta, the radar's entered/left counts and the
        // movement axis of the portfolio map went dark together.
        var earlier = Fixtures.reportWithTrends(Fixtures.assemblingRequest(), "em-1.0.0", List.of("a"), 50.0);
        var later = Fixtures.reportWithTrends(Fixtures.assemblingRequest(), "em-1.0.0", List.of("a", "b"), 50.0);

        var delta = ReportDelta.between(later, earlier);

        assertThat(delta.isAvailable()).isTrue();
        assertThat(delta.entered()).extracting(ReportDelta.Entry::trendKey).containsExactly("b");
    }

    @Test
    void aTopicPresentOnlyInTheNewVersionCountsAsEntered() {
        var delta = ReportDelta.between(reportWith(List.of("a", "b")), reportWith(List.of("a")));

        assertThat(delta.entered()).extracting(ReportDelta.Entry::trendKey).containsExactly("b");
        assertThat(delta.left()).isEmpty();
    }

    @Test
    void aTopicPresentOnlyInTheOldVersionCountsAsLeft() {
        var delta = ReportDelta.between(reportWith(List.of("a")), reportWith(List.of("a", "b")));

        assertThat(delta.left()).extracting(ReportDelta.Entry::trendKey).containsExactly("b");
        assertThat(delta.entered()).isEmpty();
    }

    @Test
    void rankChangeIsPositiveWhenATopicClimbs() {
        // Ranks count downwards, so the raw difference would read backwards to a human. The field is
        // defined as "places climbed" precisely so the sign matches the word.
        var delta = ReportDelta.between(reportWith(List.of("b", "a")), reportWith(List.of("a", "b")));

        var b = delta.stayed().stream()
                .filter(entry -> entry.trendKey().equals("b"))
                .findFirst()
                .orElseThrow();
        assertThat(b.rankChange()).isEqualTo(1);
    }

    @Test
    void changeIsUnknownRatherThanZeroForATopicPresentInOnlyOneVersion() {
        // Zero would read as "held its position", which is a claim. There is no previous rank to
        // subtract, and saying nothing is the only truthful answer.
        var delta = ReportDelta.between(reportWith(List.of("a", "b")), reportWith(List.of("a")));

        assertThat(delta.entered().getFirst().rankChange()).isNull();
        assertThat(delta.entered().getFirst().scoreChange()).isNull();
    }

    @Test
    void identicalVersionsProduceAnEmptyButAvailableDelta() {
        // "Nothing changed" is a useful answer and must stay distinguishable from "nothing to
        // compare with" — the whole reason `unavailableReason` exists.
        var delta = ReportDelta.between(reportWith(List.of("a", "b")), reportWith(List.of("a", "b")));

        assertThat(delta.isAvailable()).isTrue();
        assertThat(delta.entered()).isEmpty();
        assertThat(delta.left()).isEmpty();
        assertThat(delta.stayed()).hasSize(2);
        assertThat(delta.stayed())
                .allSatisfy(entry -> assertThat(entry.rankChange()).isZero());
    }

    @Test
    void aChangedWeightProfileRefusesToCompare() {
        // Профиль задаёт веса индикаторов, а с недавних пор создаётся кнопкой на экране методологии.
        // Версия формул при этом не меняется: `em-1.0.0` в обоих отчётах. Довод же был записан не
        // про версию — разность баллов, посчитанных разными весами, есть смена линейки.
        var delta = ReportDelta.between(
                reportWithMethodology(new MethodologyRef("em-1.0.0", OTHER_PROFILE, "WEIGHTED_GEOMETRIC")),
                reportWithMethodology(new MethodologyRef("em-1.0.0", Fixtures.PROFILE_ID, "WEIGHTED_GEOMETRIC")));

        assertThat(delta.isAvailable()).isFalse();
        assertThat(delta.unavailableReason()).isEqualTo(ReportDelta.Unavailable.METHODOLOGY_CHANGED);
    }

    @Test
    void aChangedAggregatorRefusesToCompare() {
        // Агрегатор — поле профиля, и случай самый резкий: `MIN_BOUND` ограничен слабейшим
        // индикатором, взвешенное среднее — нет. Шкалы разные буквально.
        var delta = ReportDelta.between(
                reportWithMethodology(new MethodologyRef("em-1.0.0", Fixtures.PROFILE_ID, "MIN_BOUND")),
                reportWithMethodology(new MethodologyRef("em-1.0.0", Fixtures.PROFILE_ID, "WEIGHTED_GEOMETRIC")));

        assertThat(delta.isAvailable()).isFalse();
        assertThat(delta.unavailableReason()).isEqualTo(ReportDelta.Unavailable.METHODOLOGY_CHANGED);
    }

    @Test
    void theSameMethodologyStillCompares() {
        // Канарейка: правило, отказывающее всегда, тоже «не сравнивает разные линейки» — и не
        // сравнивает вообще ничего.
        var delta = ReportDelta.between(
                reportWithMethodology(new MethodologyRef("em-1.0.0", Fixtures.PROFILE_ID, "WEIGHTED_GEOMETRIC")),
                reportWithMethodology(new MethodologyRef("em-1.0.0", Fixtures.PROFILE_ID, "WEIGHTED_GEOMETRIC")));

        assertThat(delta.isAvailable()).isTrue();
    }

    @Test
    void aChangedMethodologyVersionRefusesToCompare() {
        // A different methodology is a different scale. Subtracting scores measured on two scales
        // produces a number that looks like a change in the world and is a change of ruler.
        var delta = ReportDelta.between(
                reportWith("em-2.0.0", List.of("a"), 50.0), reportWith("em-1.0.0", List.of("a"), 50.0));

        assertThat(delta.isAvailable()).isFalse();
        assertThat(delta.unavailableReason()).isEqualTo(ReportDelta.Unavailable.METHODOLOGY_CHANGED);
        assertThat(delta.stayed()).isEmpty();
    }

    @Test
    void noPreviousVersionIsAnAnswerRatherThanAnError() {
        var delta = ReportDelta.between(reportWith(List.of("a")), null);

        assertThat(delta.isAvailable()).isFalse();
        assertThat(delta.unavailableReason()).isEqualTo(ReportDelta.Unavailable.NO_PREVIOUS_VERSION);
    }

    @Test
    void aShrunkTopShowsTheDifferenceAsDepartures() {
        // topN lowered between runs: the topics did not vanish from the field, they left the top.
        var delta = ReportDelta.between(reportWith(List.of("a")), reportWith(List.of("a", "b", "c")));

        assertThat(delta.left()).extracting(ReportDelta.Entry::trendKey).containsExactly("b", "c");
    }

    @Test
    void acceleratingTopicsComeFirstAndTheOrderIsDeterministic() {
        // Two runs over the same input must order identically: the key closes the comparator so the
        // result cannot depend on iteration order (ADR-0015 reaches the read side too).
        var current = reportWith(List.of("c", "a", "b"));
        var previous = reportWith(List.of("a", "b", "c"));

        var first = ReportDelta.between(current, previous);
        var second = ReportDelta.between(current, previous);

        assertThat(first.stayed()).extracting(ReportDelta.Entry::trendKey).containsExactly("c", "a", "b");
        assertThat(second.stayed()).isEqualTo(first.stayed());
    }

    @Test
    void enteredAndLeftAreOrderedByRankInTheVersionTheyBelongTo() {
        var delta = ReportDelta.between(reportWith(List.of("x", "y", "a")), reportWith(List.of("a", "p", "q")));

        assertThat(delta.entered()).extracting(ReportDelta.Entry::rank).containsExactly(1, 2);
        assertThat(delta.left()).extracting(ReportDelta.Entry::rank).containsExactly(2, 3);
    }

    @Test
    void theGroupsAreImmutableToTheirCaller() {
        var delta = ReportDelta.between(reportWith(List.of("a", "b")), reportWith(List.of("a")));

        assertThat(delta.entered()).isUnmodifiable();
        assertThat(delta.left()).isUnmodifiable();
        assertThat(delta.stayed()).isUnmodifiable();
    }
}
