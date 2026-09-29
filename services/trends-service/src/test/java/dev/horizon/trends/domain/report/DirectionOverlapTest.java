package dev.horizon.trends.domain.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.horizon.trends.support.Fixtures;

/**
 * Topics shared between tracked directions (BR-A27…BR-A31).
 *
 * <p>A topic in one direction is an observation; the same topic independently in two is convergence,
 * and the methodology cannot see it — every indicator is computed inside the corpus of a single
 * question. That makes this the one calculation in the product that reads directions together, and
 * the one place where a mistake shows up as a claim about the field rather than as a broken screen.
 */
class DirectionOverlapTest {

    private static final UUID AI = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001");
    private static final UUID FINTECH = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000002");
    private static final UUID SECURITY = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000003");
    private static final UUID FOURTH = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000004");

    private static List<String> keysOf(DirectionOverlap overlap) {
        return overlap.topics().stream()
                .map(DirectionOverlap.SharedTopic::trendKey)
                .toList();
    }

    /**
     * The same direction, but with every topic renamed — so that "whose wording wins" is a question
     * with an observable answer. Without it both reports call the key "Тренд k" and any rule at all
     * would pass.
     */
    private static DirectionOverlap.DirectionReport renamed(DirectionOverlap.DirectionReport direction, String suffix) {
        var trends = direction.report().trends().stream()
                .map(trend -> new RankedTrend(
                        trend.rank(),
                        trend.trendKey(),
                        trend.title() + suffix,
                        trend.definition(),
                        trend.motivation(),
                        trend.caseExample(),
                        trend.assessment(),
                        trend.lifecycleStage(),
                        trend.firstMentionYear(),
                        trend.totalDocuments(),
                        trend.burst(),
                        trend.timeline(),
                        trend.evidence()))
                .toList();
        var report = TrendReport.create(
                direction.report().researchRequestId(),
                Fixtures.requester(),
                direction.report().query(),
                direction.report().methodology(),
                Fixtures.SNAPSHOT_ID,
                direction.report().coverage(),
                trends,
                Math.max(trends.size(), 5),
                direction.report().version(),
                null,
                Fixtures.NOW);
        return new DirectionOverlap.DirectionReport(
                direction.savedDomainId(), direction.query(), direction.reportId(), report);
    }

    /** A direction whose report holds exactly the given topic keys, ranked in the order listed. */
    private static DirectionOverlap.DirectionReport direction(UUID id, String query, String... keys) {
        var report = Fixtures.reportWithTrends(Fixtures.pendingRequest(), "em-1.0.0", List.of(keys), 90.0);
        return new DirectionOverlap.DirectionReport(id, query, UUID.randomUUID(), report);
    }

    @Test
    void aTopicInOneDirectionIsNotAnOverlap() {
        // P2. A list that admits everything answers a different question than the one it exists for.
        var overlap =
                DirectionOverlap.of(List.of(direction(AI, "ии", "a", "b"), direction(FINTECH, "финтех", "c", "d")), 0);

        assertThat(overlap.topics()).isEmpty();
        assertThat(overlap.directionsCompared()).isEqualTo(2);
    }

    @Test
    void aTopicInTwoDirectionsIsReportedWithBoth() {
        var overlap = DirectionOverlap.of(
                List.of(direction(AI, "ии", "a", "shared"), direction(FINTECH, "финтех", "shared", "d")), 0);

        assertThat(overlap.topics()).hasSize(1);
        var topic = overlap.topics().getFirst();
        assertThat(topic.trendKey()).isEqualTo("shared");
        // Сильнейшее вхождение первым: в «финтехе» тема на первом месте, в «ии» — на втором. Порядок
        // загрузки направлений здесь обратный, и оставить его значило бы показать сверху то
        // вхождение, где тема заметна меньше.
        assertThat(topic.appearances())
                .extracting(DirectionOverlap.SharedTopic.Appearance::query)
                .containsExactly("финтех", "ии");
    }

    @Test
    void everyAppearanceCarriesItsRankAndItsReport() {
        // BR-A28, BR-A29. "In both" is only checkable when both halves open, and second place in one
        // direction against fifteenth in another is a different statement than the bare fact.
        var ai = direction(AI, "ии", "x", "shared");
        var fintech = direction(FINTECH, "финтех", "shared", "y");

        var topic = DirectionOverlap.of(List.of(ai, fintech), 0).topics().getFirst();

        assertThat(topic.appearances())
                .extracting(
                        DirectionOverlap.SharedTopic.Appearance::rank,
                        DirectionOverlap.SharedTopic.Appearance::reportId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(1, fintech.reportId()),
                        org.assertj.core.groups.Tuple.tuple(2, ai.reportId()));
    }

    @Test
    void strongerSignalsComeFirst() {
        // P4. Being in three directions outranks being in two regardless of position, because the
        // number of fields a topic crossed into is the signal this screen exists to surface.
        var overlap = DirectionOverlap.of(
                List.of(
                        direction(AI, "ии", "pair", "triple"),
                        direction(FINTECH, "финтех", "pair", "triple"),
                        direction(SECURITY, "безопасность", "triple", "z")),
                0);

        assertThat(overlap.topics())
                .extracting(DirectionOverlap.SharedTopic::trendKey)
                .containsExactly("triple", "pair");
    }

    @Test
    void amongEquallyWidespreadTopicsTheMoreVisibleOneComesFirst() {
        // P4, second key: both crossed two directions, so the tiebreak is how prominent the topic is
        // where it does appear. "buried" is met first, so insertion order alone would put it on top —
        // which is exactly the mistake this comparator exists to prevent.
        var overlap = DirectionOverlap.of(
                List.of(
                        direction(AI, "ии", "фон-1", "buried"),
                        direction(FINTECH, "финтех", "фон-2", "buried"),
                        direction(SECURITY, "безопасность", "visible"),
                        direction(FOURTH, "энергетика", "visible")),
                0);

        assertThat(overlap.topics())
                .extracting(DirectionOverlap.SharedTopic::trendKey)
                .containsExactly("visible", "buried");
    }

    @Test
    void theOrderDoesNotDependOnWhichDirectionWasReadFirst() {
        // ADR-0015. Both topics cross both directions at the same best rank, so nothing but the key
        // separates them. Without that last comparator the answer would follow the order the
        // directions happened to be loaded in — and the screen would reshuffle when a direction is
        // saved or renamed, with nothing having changed in the field.
        var ai = direction(AI, "ии", "бета", "альфа");
        var fintech = direction(FINTECH, "финтех", "альфа", "бета");

        var oneWay = DirectionOverlap.of(List.of(ai, fintech), 0);
        var otherWay = DirectionOverlap.of(List.of(fintech, ai), 0);

        assertThat(keysOf(oneWay)).containsExactly("альфа", "бета");
        assertThat(keysOf(otherWay)).isEqualTo(keysOf(oneWay));
    }

    @Test
    void oneDirectionCountsOnce_evenIfItsReportNamesTheTopicTwice() {
        // P3. `TrendReport` pins ranks to 1..n but says nothing about keys being distinct — its own
        // `findByKey` takes the first match, which is the admission. Without the guard, "in two
        // directions" would be printable from a single one.
        var repeated = direction(AI, "ии", "дубль", "дубль");
        var other = direction(FINTECH, "финтех", "дубль");

        var topic = DirectionOverlap.of(List.of(repeated, other), 0).topics().getFirst();

        assertThat(topic.appearances()).hasSize(2);
        assertThat(topic.appearances())
                .extracting(DirectionOverlap.SharedTopic.Appearance::savedDomainId)
                .containsExactly(AI, FINTECH);
    }

    @Test
    void nothingToCompareIsNotTheSameAsNothingInCommon() {
        // BR-A31. Both give an empty list, and the two counters are what lets the client tell them
        // apart — "you have one analysed direction" must not read as "your fields do not converge".
        var nothingRun = DirectionOverlap.of(List.of(), 3);
        var oneAnalysed = DirectionOverlap.of(List.of(direction(AI, "ии", "a")), 2);

        assertThat(nothingRun.directionsCompared()).isZero();
        assertThat(nothingRun.directionsSkipped()).isEqualTo(3);
        assertThat(oneAnalysed.directionsCompared()).isOne();
        assertThat(oneAnalysed.directionsSkipped()).isEqualTo(2);
    }

    @Test
    void theTopicIsNamedAsTheDirectionThatRanksItHighestNamesIt() {
        // The same key is phrased slightly differently from report to report; the wording from where
        // the topic stands highest is the one the analyst is most likely to recognise. Both orders of
        // input are checked, because the rule must not degrade into "whichever was read first".
        var low = renamed(direction(AI, "ии", "фон", "общая"), " (в ии)");
        var high = renamed(direction(FINTECH, "финтех", "общая"), " (в финтехе)");

        var byLowFirst = DirectionOverlap.of(List.of(low, high), 0).topics().getFirst();
        var byHighFirst = DirectionOverlap.of(List.of(high, low), 0).topics().getFirst();

        assertThat(byLowFirst.title()).isEqualTo("Тренд общая (в финтехе)");
        assertThat(byHighFirst.title()).isEqualTo(byLowFirst.title());
        assertThat(byLowFirst.bestRank()).isOne();
    }

    @Test
    void theResultIsImmutableToItsCaller() {
        var overlap =
                DirectionOverlap.of(List.of(direction(AI, "ии", "shared"), direction(FINTECH, "финтех", "shared")), 0);

        assertThat(overlap.topics()).isUnmodifiable();
        assertThat(overlap.topics().getFirst().appearances()).isUnmodifiable();
    }

    @Test
    void anEmptyPortfolioIsAnAnswerRatherThanAnError() {
        var overlap = DirectionOverlap.of(List.of(), 0);

        assertThat(overlap.topics()).isEmpty();
        assertThat(overlap.directionsCompared()).isZero();
    }

    @Test
    void nothingHereCarriesAScore() {
        // P7, and the reason it is a rule rather than an omission: a score is normalised inside its
        // own corpus (§12), so two directions' scores share no scale — yet a number placed beside
        // another gets compared whatever the caption says. Reflection rather than an eyeball over
        // the record, because the field that breaks this will be added by someone who has not read
        // §12, and the only thing that will stop them is a red test.
        var carriers = List.of(
                DirectionOverlap.class,
                DirectionOverlap.SharedTopic.class,
                DirectionOverlap.SharedTopic.Appearance.class);

        for (Class<?> carrier : carriers) {
            for (var component : carrier.getRecordComponents()) {
                assertThat(component.getName().toLowerCase(java.util.Locale.ROOT))
                        .as("%s.%s", carrier.getSimpleName(), component.getName())
                        .doesNotContain("score")
                        .doesNotContain("confidence");
            }
        }
    }
}
