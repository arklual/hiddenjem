package dev.horizon.trends.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.horizon.trends.application.port.PageResult;
import dev.horizon.trends.application.port.ReportCache;
import dev.horizon.trends.application.port.ResearchRequestRepository;
import dev.horizon.trends.application.port.TrendReportRepository;
import dev.horizon.trends.domain.report.ReportDelta;
import dev.horizon.trends.domain.report.TrendReport;
import dev.horizon.trends.domain.report.TrendReportId;
import dev.horizon.trends.domain.research.AnalysisParameters;
import dev.horizon.trends.domain.research.ReportViewer;
import dev.horizon.trends.domain.research.RequesterRef;
import dev.horizon.trends.domain.research.ResearchRequest;
import dev.horizon.trends.domain.research.ResearchRequestId;
import dev.horizon.trends.domain.research.ResearchStatus;
import dev.horizon.trends.domain.research.TechnologyDomainQuery;
import dev.horizon.trends.support.Fixtures;

/**
 * Reading the delta across two runs of the same direction (BR-A37, JTBD-13).
 *
 * <p>Writing the version link was not enough, and this file exists because that gap shipped: the
 * comparison itself refused any pair of reports from different requests, and every submission makes
 * a new request — so the delta answered "nothing to compare" under every possible state of the
 * system while the saga dutifully wrote a predecessor nobody read. The test that would have caught
 * it is one that reads the delta, not one that inspects what was saved.
 *
 * <p>The second thing covered here is the privacy consequence of the fix. The lineage of a direction
 * is shared between users, so a predecessor may belong to someone else's request — and the rule that
 * used to stop that read was the same request-equality check that was breaking the feature.
 */
class DeltaAcrossVersionsTest {

    private static final UUID STRANGER = UUID.fromString("99999999-9999-4999-8999-999999999999");
    private static final String DIRECTION = "квантовые вычисления";

    /** A completed request of the given user, with a report of the given topics attached. */
    private record Run(ResearchRequest request, TrendReport report) {}

    private static Run run(UUID userId, String query, List<String> keys, int version, TrendReportId previous) {
        var request = ResearchRequest.submit(
                new RequesterRef(userId, Fixtures.ORGANIZATION_ID),
                TechnologyDomainQuery.of(query),
                Fixtures.parameters(),
                null,
                Duration.ofMinutes(10),
                Fixtures.NOW);
        request.startCollecting(Fixtures.NOW.plusSeconds(1));
        request.corpusCollected(Fixtures.SNAPSHOT_ID, Fixtures.corpusCoverage(), Fixtures.NOW.plusSeconds(2));
        request.startAssembling(UUID.randomUUID(), Fixtures.NOW.plusSeconds(3));
        var base = Fixtures.reportWithTrends(request, "em-1.0.0", keys, 50.0);
        var report = TrendReport.create(
                request.id(),
                new RequesterRef(userId, Fixtures.ORGANIZATION_ID),
                request.query(),
                base.methodology(),
                Fixtures.SNAPSHOT_ID,
                base.coverage(),
                base.trends(),
                Math.max(keys.size(), 5),
                version,
                previous,
                Fixtures.NOW.plusSeconds(90));
        return new Run(request, report);
    }

    private static final class Reports implements TrendReportRepository {
        private final List<TrendReport> stored;

        private Reports(List<TrendReport> stored) {
            this.stored = stored;
        }

        @Override
        public Optional<TrendReport> findById(TrendReportId id) {
            return stored.stream().filter(report -> report.id().equals(id)).findFirst();
        }

        @Override
        public Optional<TrendReport> findLatestForDirection(
                String normalizedQuery, String paramsDiscriminator, ResearchRequestId excludingRequestId) {
            return Optional.empty();
        }

        @Override
        public int nextVersionFor(String normalizedQuery, String paramsDiscriminator) {
            return 1;
        }

        @Override
        public java.util.Map<String, String> titlesByTrendKey(
                String normalizedQuery, java.util.Collection<String> trendKeys) {
            return java.util.Map.of();
        }

        @Override
        public TrendReport save(TrendReport report) {
            return report;
        }
    }

    private static final class Requests implements ResearchRequestRepository {
        private final List<ResearchRequest> stored;

        private Requests(List<ResearchRequest> stored) {
            this.stored = stored;
        }

        @Override
        public Optional<ResearchRequest> findById(ResearchRequestId id) {
            return stored.stream().filter(request -> request.id().equals(id)).findFirst();
        }

        @Override
        public ResearchRequest save(ResearchRequest request) {
            return request;
        }

        @Override
        public Optional<ResearchRequest> findByIdForUpdate(ResearchRequestId id) {
            return findById(id);
        }

        @Override
        public Optional<ResearchRequest> findByIdempotencyKey(UUID userId, String idempotencyKey) {
            return Optional.empty();
        }

        @Override
        public PageResult<ResearchRequest> findHistory(
                ReportViewer viewer, boolean onlyMine, ResearchStatus status, int page, int size) {
            return new PageResult<>(List.of(), 0, page, size);
        }

        @Override
        public List<ResearchRequest> findOverdue(Instant now, int limit) {
            return List.of();
        }

        @Override
        public Optional<ResearchRequest> findActive(
                UUID userId, String normalizedQuery, AnalysisParameters parameters) {
            return Optional.empty();
        }

        @Override
        public Optional<ResearchRequest> findFreshCompleted(
                String normalizedQuery, AnalysisParameters parameters, UUID organizationId, Instant notOlderThan) {
            return Optional.empty();
        }

        @Override
        public long countSubmittedSince(UUID userId, Instant since) {
            return 0;
        }

        @Override
        public long countSubmittedByOrganizationSince(UUID organizationId, Instant since) {
            return 0;
        }
    }

    private static final class NoCache implements ReportCache {
        @Override
        public Optional<TrendReport> get(TrendReportId id) {
            return Optional.empty();
        }

        @Override
        public void put(TrendReport report) {}

        @Override
        public void evict(TrendReportId id) {}
    }

    private static GetTrendReportUseCase useCase(List<Run> runs) {
        return new GetTrendReportUseCase(
                new Reports(runs.stream().map(Run::report).toList()),
                new Requests(runs.stream().map(Run::request).toList()),
                new NoCache());
    }

    @Test
    void twoRunsOfTheSameDirectionAreActuallyCompared() {
        var first = run(Fixtures.USER_ID, DIRECTION, List.of("a", "b"), 1, null);
        var second = run(
                Fixtures.USER_ID,
                DIRECTION,
                List.of("a", "c"),
                2,
                first.report().id());

        var delta = useCase(List.of(first, second)).delta(second.report().id(), ReportViewer.of(Fixtures.USER_ID));

        assertThat(delta.isAvailable())
                .as("дельта — то, ради чего цепочка версий и заводилась")
                .isTrue();
        assertThat(delta.entered()).extracting(ReportDelta.Entry::trendKey).containsExactly("c");
        assertThat(delta.left()).extracting(ReportDelta.Entry::trendKey).containsExactly("b");
    }

    @Test
    void aPredecessorTheCallerMayNotReadIsNothingToCompareWith() {
        // Цепочка направления общая, значит предшественник может принадлежать чужому запросу. Через
        // этот эндпойнт его темы, ключи и баллы наружу не выходят — и отказ выглядит как «сравнивать
        // не с чем», потому что вопрос был про этот отчёт, а не про тот.
        var strangers = run(STRANGER, DIRECTION, List.of("a", "b"), 1, null);
        var mine = run(
                Fixtures.USER_ID,
                DIRECTION,
                List.of("a", "c"),
                2,
                strangers.report().id());

        var delta = useCase(List.of(strangers, mine)).delta(mine.report().id(), ReportViewer.of(Fixtures.USER_ID));

        assertThat(delta.isAvailable()).isFalse();
        assertThat(delta.unavailableReason()).isEqualTo(ReportDelta.Unavailable.NO_PREVIOUS_VERSION);
    }

    @Test
    void anAdministratorSeesTheComparisonAcrossOwners() {
        // Та же проверка видимости, что и на чтении отчёта, — не вторая, которая разъедется с первой.
        var strangers = run(STRANGER, DIRECTION, List.of("a", "b"), 1, null);
        var mine = run(
                Fixtures.USER_ID,
                DIRECTION,
                List.of("a", "c"),
                2,
                strangers.report().id());

        var delta = useCase(List.of(strangers, mine))
                .delta(mine.report().id(), new ReportViewer(Fixtures.USER_ID, null, true));

        assertThat(delta.isAvailable()).isTrue();
    }

    @Test
    void aMissingPredecessorIsNothingToCompareWithRatherThanAFailure() {
        var mine = run(Fixtures.USER_ID, DIRECTION, List.of("a"), 2, new TrendReportId(UUID.randomUUID()));

        var delta = useCase(List.of(mine)).delta(mine.report().id(), ReportViewer.of(Fixtures.USER_ID));

        assertThat(delta.isAvailable()).isFalse();
    }

    @Test
    void theFirstVersionHasNothingToCompareWith() {
        var only = run(Fixtures.USER_ID, DIRECTION, List.of("a"), 1, null);

        var delta = useCase(List.of(only)).delta(only.report().id(), ReportViewer.of(Fixtures.USER_ID));

        assertThat(delta.isAvailable()).isFalse();
    }
}
