package dev.horizon.trends.domain.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import dev.horizon.trends.support.Fixtures;

/**
 * A trend report is evidence used to justify investment decisions. Every rule enforced here exists
 * because violating it would let the platform publish a claim it cannot substantiate — the one
 * failure mode the product cannot tolerate (domain-model doc §4.3, invariants J1–J6).
 */
class TrendReportInvariantsTest {

    private static TrendReport create(List<RankedTrend> trends, int requestedTopN) {
        var request = Fixtures.assemblingRequest();
        return TrendReport.create(
                request.id(),
                Fixtures.requester(),
                Fixtures.query(),
                Fixtures.methodology(),
                Fixtures.SNAPSHOT_ID,
                Fixtures.coverage(),
                trends,
                requestedTopN,
                1,
                null,
                Fixtures.NOW);
    }

    private static List<RankedTrend> trends(int count) {
        List<RankedTrend> result = new ArrayList<>(count);
        for (int rank = 1; rank <= count; rank++) {
            result.add(Fixtures.trend(rank, "trend-" + rank));
        }
        return result;
    }

    @Nested
    @DisplayName("J1 — ranks form the contiguous sequence 1..n")
    class ContiguousRanks {

        @Test
        @DisplayName("accepts a well-formed ranking regardless of the order it is supplied in")
        void acceptsShuffledInput() {
            var shuffled = new ArrayList<>(trends(5));
            java.util.Collections.reverse(shuffled);

            var report = create(shuffled, 15);

            assertThat(report.trends()).extracting(RankedTrend::rank).containsExactly(1, 2, 3, 4, 5);
        }

        @Test
        @DisplayName("rejects a gap in the ranking")
        void rejectsGap() {
            var withGap = List.of(Fixtures.trend(1, "a"), Fixtures.trend(3, "c"));

            assertThatThrownBy(() -> create(withGap, 15))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("J1");
        }

        @Test
        @DisplayName("rejects a duplicated rank")
        void rejectsDuplicateRank() {
            var duplicated = List.of(Fixtures.trend(1, "a"), Fixtures.trend(1, "b"));

            assertThatThrownBy(() -> create(duplicated, 15)).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects a ranking that does not start at 1")
        void rejectsOffsetStart() {
            assertThatThrownBy(() -> create(List.of(Fixtures.trend(2, "a")), 15))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("J2 — no trend without evidence (BR-A6)")
    class EvidenceRequired {

        @Test
        @DisplayName("a trend cannot be constructed without at least one source")
        void trendWithoutEvidenceIsUnrepresentable() {
            var base = Fixtures.trend(1, "a");

            assertThatThrownBy(() -> new RankedTrend(
                            base.rank(),
                            base.trendKey(),
                            base.title(),
                            base.definition(),
                            base.motivation(),
                            null,
                            base.assessment(),
                            base.lifecycleStage(),
                            base.firstMentionYear(),
                            base.totalDocuments(),
                            base.burst(),
                            base.timeline(),
                            List.of()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("evidence");
        }

        @Test
        @DisplayName("a case example may not point past the end of the evidence list")
        void caseExampleMustReferenceRealEvidence() {
            var base = Fixtures.trend(1, "a");
            var danglingCase = new CaseExample(
                    "Acme Research", CaseExample.OrganizationType.COMPANY, "US", null, 99, CaseExample.Basis.PATENT);

            assertThatThrownBy(() -> new RankedTrend(
                            base.rank(),
                            base.trendKey(),
                            base.title(),
                            base.definition(),
                            base.motivation(),
                            danglingCase,
                            base.assessment(),
                            base.lifecycleStage(),
                            base.firstMentionYear(),
                            base.totalDocuments(),
                            base.burst(),
                            base.timeline(),
                            base.evidence()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("caseExample");
        }

        @Test
        @DisplayName("a motivation attribution may not point past the end of the evidence list")
        void motivationMustReferenceRealEvidence() {
            var base = Fixtures.trend(1, "a");
            var dangling = new Motivation(
                    "Проблема", "Преимущество", List.of(new Motivation.Attribution("problem", 42, "Проблема")));

            assertThatThrownBy(() -> new RankedTrend(
                            base.rank(),
                            base.trendKey(),
                            base.title(),
                            base.definition(),
                            dangling,
                            null,
                            base.assessment(),
                            base.lifecycleStage(),
                            base.firstMentionYear(),
                            base.totalDocuments(),
                            base.burst(),
                            base.timeline(),
                            base.evidence()))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("motivation");
        }
    }

    @Nested
    @DisplayName("J3 — the report never exceeds what was asked for")
    class TopNRespected {

        @Test
        void rejectsMoreTrendsThanRequested() {
            assertThatThrownBy(() -> create(trends(16), 15))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("J3");
        }

        @Test
        @DisplayName("fewer trends than requested is legal but marked truncated")
        void fewerTrendsIsMarkedTruncated() {
            var report = create(trends(9), 15);

            assertThat(report.truncated()).isTrue();
            assertThat(create(trends(15), 15).truncated()).isFalse();
        }
    }

    @Nested
    @DisplayName("J4 — indicator weights sum to one")
    class WeightsSumToOne {

        @Test
        @DisplayName("rejects an assessment whose weights do not sum to 1")
        void rejectsUnnormalisedWeights() {
            // Weights that do not sum to one silently change what the score means: the same number
            // would no longer be comparable between two reports.
            var broken = List.of(
                    new IndicatorScore("novelty", 0.5, 0.5, 0.7, 0.2, null, null),
                    new IndicatorScore("growth", 0.5, 0.2, 0.8, 0.3, null, null));

            assertThatThrownBy(() -> new EmergenceAssessment(50.0, 0.8, false, broken))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("J4");
        }

        @Test
        void acceptsNormalisedWeights() {
            assertThatCode(() -> new EmergenceAssessment(50.0, 0.8, false, Fixtures.indicators()))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("reports the indicator that limits the score the most")
        void identifiesLimitingIndicator() {
            var assessment = new EmergenceAssessment(50.0, 0.8, false, Fixtures.indicators());

            var limiting = assessment.limitingIndicator();

            assertThat(limiting.shortfallShare())
                    .isEqualTo(Fixtures.indicators().stream()
                            .mapToDouble(IndicatorScore::shortfallShare)
                            .max()
                            .orElseThrow());
        }
    }

    @Nested
    @DisplayName("immutability (BRULE-5)")
    class Immutability {

        @Test
        @DisplayName("the trend list handed back cannot be modified")
        void trendListIsUnmodifiable() {
            var report = create(trends(3), 15);

            assertThatThrownBy(() -> report.trends().add(Fixtures.trend(4, "d")))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("a recomputation is a new version linked to the previous one")
        void recomputationCreatesANewVersion() {
            var first = create(trends(3), 15);

            var second = TrendReport.create(
                    first.researchRequestId(),
                    Fixtures.requester(),
                    Fixtures.query(),
                    Fixtures.methodology(),
                    Fixtures.SNAPSHOT_ID,
                    Fixtures.coverage(),
                    trends(4),
                    15,
                    2,
                    first.id(),
                    Fixtures.NOW.plusSeconds(3600));

            assertThat(second.version()).isEqualTo(2);
            assertThat(second.previousVersionId()).contains(first.id());
            assertThat(second.id()).isNotEqualTo(first.id());
        }
    }

    @Test
    @DisplayName("publishes exactly one TrendReportGenerated event, and only once")
    void publishesGenerationEventOnce() {
        var report = create(trends(3), 15);

        var first = report.drainEvents();
        var second = report.drainEvents();

        assertThat(first).hasSize(1);
        assertThat(first.get(0).eventType()).isEqualTo("horizon.trends.TrendReportGenerated");
        // Draining twice must not republish: the outbox would then carry a duplicate fact.
        assertThat(second).isEmpty();
    }

    @Test
    @DisplayName("coverage derives partiality from the facts, not from the caller")
    void coverageDerivesPartiality() {
        var coverage = new Coverage(
                100,
                400,
                List.of("arxiv"),
                List.of("uspto"),
                false,
                true,
                List.of(),
                0,
                false,
                LocalDate.of(2019, 1, 1),
                LocalDate.of(2026, 1, 1));

        assertThat(coverage.partial()).isTrue();
    }

    @Test
    @DisplayName("a trend can be found by its stable key")
    void findsTrendByKey() {
        var report = create(trends(5), 15);

        assertThat(report.findByKey("trend-3")).isPresent();
        assertThat(report.findByKey("nope")).isEmpty();
    }
}
