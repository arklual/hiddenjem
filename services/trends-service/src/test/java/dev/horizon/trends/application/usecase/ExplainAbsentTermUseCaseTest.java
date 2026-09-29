package dev.horizon.trends.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.horizon.platform.common.error.HorizonException;
import dev.horizon.trends.application.port.MethodologyProfileRepository;
import dev.horizon.trends.application.port.PageResult;
import dev.horizon.trends.application.port.ReportCache;
import dev.horizon.trends.application.port.ResearchRequestRepository;
import dev.horizon.trends.application.port.TermTraceExplainer;
import dev.horizon.trends.application.port.TrendReportRepository;
import dev.horizon.trends.domain.methodology.MethodologyProfile;
import dev.horizon.trends.domain.report.TrendReport;
import dev.horizon.trends.domain.report.TrendReportId;
import dev.horizon.trends.domain.research.AnalysisParameters;
import dev.horizon.trends.domain.research.ReportViewer;
import dev.horizon.trends.domain.research.ResearchRequest;
import dev.horizon.trends.domain.research.ResearchRequestId;
import dev.horizon.trends.domain.research.ResearchStatus;
import dev.horizon.trends.support.Fixtures;

/**
 * The rules that keep a trace answer trustworthy.
 *
 * <p>Two of them carry the feature: the explanation must be no more visible than the report it
 * describes, and it must be computed against the snapshot that produced that report. An explanation
 * against a different corpus would be a truthful answer to a question nobody asked — and worse than
 * no answer, because it would look right.
 */
class ExplainAbsentTermUseCaseTest {

    private RecordingExplainer explainer;
    private ExplainAbsentTermUseCase useCase;
    private TrendReport report;
    private ResearchRequest request;

    @BeforeEach
    void setUp() {
        request = Fixtures.assemblingRequest();
        report = Fixtures.report(request, 3);
        explainer = new RecordingExplainer();

        ResearchRequestRepository requests = new StubRequests(request);
        MethodologyProfileRepository profiles = new StubProfiles();
        // The real access-control use case, not a stand-in: the rule under test is that an
        // explanation is exactly as visible as its report, and a stubbed check would assert nothing.
        var reports = new GetTrendReportUseCase(new StubReports(report), requests, new NoCache());
        useCase = new ExplainAbsentTermUseCase(reports, requests, profiles, explainer);
    }

    @Test
    void theEngineIsNeverAwaitedInsideATransaction() {
        // Повтор анализа идёт минутами; транзакция, открытая всё это время, закрывалась базой по
        // таймауту простоя, и готовый ответ движка терялся на коммите (стенд 2026-09-28).
        boolean[] open = {false};
        boolean[] calledInside = {false};
        TermTraceExplainer watching = replay -> {
            calledInside[0] = open[0];
            return explainer.explain(replay);
        };
        var requests = new StubRequests(request);
        var reports = new GetTrendReportUseCase(new StubReports(report), requests, new NoCache());
        var guarded = new ExplainAbsentTermUseCase(
                reports,
                requests,
                new StubProfiles(),
                watching,
                new ExplainAbsentTermUseCase.ReadOnly() {
                    @Override
                    public <T> T run(java.util.function.Supplier<T> work) {
                        open[0] = true;
                        try {
                            return work.get();
                        } finally {
                            open[0] = false;
                        }
                    }
                });

        guarded.explain(report.id(), List.of("sbom"), ReportViewer.of(Fixtures.USER_ID));

        assertThat(explainer.last).isNotNull();
        assertThat(calledInside[0]).as("движок вызван внутри транзакции").isFalse();
    }

    @Test
    void replaysAgainstTheSnapshotThatProducedTheReport() {
        useCase.explain(report.id(), List.of("software bill of materials"), ReportViewer.of(Fixtures.USER_ID));

        assertThat(explainer.last).isNotNull();
        assertThat(explainer.last.snapshotId()).isEqualTo(Fixtures.SNAPSHOT_ID);
        assertThat(explainer.last.normalizedQuery()).isEqualTo(request.query().normalized());
        assertThat(explainer.last.terms()).containsExactly("software bill of materials");
    }

    @Test
    void dropsBlanksAndDuplicatesBeforeSpendingAReplayOnThem() {
        useCase.explain(report.id(), List.of(" sbom ", "sbom", "   ", "zk"), ReportViewer.of(Fixtures.USER_ID));

        assertThat(explainer.last.terms()).containsExactly("sbom", "zk");
    }

    @Test
    void rejectsAnEmptyRequestRatherThanReplayingForNothing() {
        assertThatThrownBy(() -> useCase.explain(report.id(), List.of("  "), ReportViewer.of(Fixtures.USER_ID)))
                .isInstanceOf(HorizonException.class);
    }

    @Test
    void rejectsMoreTermsThanOneReplayMayAnswer() {
        // Each term rides the same replay, but the bound keeps a single call from being priced like
        // a hundred reports on the engine.
        var tooMany = new ArrayList<String>();
        for (int index = 0; index <= ExplainAbsentTermUseCase.MAX_TERMS; index++) {
            tooMany.add("term-" + index);
        }

        assertThatThrownBy(() -> useCase.explain(report.id(), tooMany, ReportViewer.of(Fixtures.USER_ID)))
                .isInstanceOf(HorizonException.class);
    }

    @Test
    void refusesACallerWhoMayNotReadTheReport() {
        // An explanation reveals what the corpus contains, so it must inherit the report's own
        // visibility rather than carry a second copy of the rule that could drift.
        assertThatThrownBy(() -> useCase.explain(report.id(), List.of("sbom"), ReportViewer.of(UUID.randomUUID())))
                .isInstanceOf(HorizonException.class);
        assertThat(explainer.last).isNull();
    }

    @Test
    void theApiPathAsksWithoutWaitingAndValidatesTheSameWay() {
        var answer = useCase.explainIfReady(report.id(), List.of(" sbom ", "sbom"), ReportViewer.of(Fixtures.USER_ID));

        assertThat(answer).isPresent();
        assertThat(explainer.last.terms()).containsExactly("sbom");
        assertThatThrownBy(() -> useCase.explainIfReady(report.id(), List.of("  "), ReportViewer.of(Fixtures.USER_ID)))
                .isInstanceOf(HorizonException.class);
    }

    @Test
    void translatesAnUnreachableEngineIntoARetryableFailure() {
        explainer.failing = true;

        assertThatThrownBy(() -> useCase.explain(report.id(), List.of("sbom"), ReportViewer.of(Fixtures.USER_ID)))
                .isInstanceOf(HorizonException.class)
                .satisfies(error -> assertThat(((HorizonException) error).type().retryable())
                        .isTrue());
    }

    // ───────────────────────────── stubs ─────────────────────────────

    private static final class RecordingExplainer implements TermTraceExplainer {
        private Request last;
        private boolean failing;

        @Override
        public Explanation explain(Request request) {
            if (failing) {
                throw new ExplanationUnavailableException("движок недоступен", null);
            }
            this.last = request;
            return new Explanation(List.of("extracted", "ranked"), List.of());
        }
    }

    private static final class StubProfiles implements MethodologyProfileRepository {
        private final MethodologyProfile profile = new MethodologyProfile(
                Fixtures.PROFILE_ID,
                "default",
                1,
                "em-1.0.0",
                MethodologyProfile.ScoreAggregator.WEIGHTED_GEOMETRIC,
                MethodologyProfile.defaultWeights(),
                MethodologyProfile.defaultParameters(),
                0.5,
                true,
                Fixtures.USER_ID,
                Instant.parse("2026-01-01T00:00:00Z"));

        @Override
        public Optional<MethodologyProfile> findById(UUID id) {
            return Optional.of(profile);
        }

        @Override
        public MethodologyProfile requireDefault() {
            return profile;
        }
    }

    private static final class StubRequests implements ResearchRequestRepository {
        private final ResearchRequest request;

        private StubRequests(ResearchRequest request) {
            this.request = request;
        }

        @Override
        public Optional<ResearchRequest> findById(ResearchRequestId id) {
            return id.equals(request.id()) ? Optional.of(request) : Optional.empty();
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
                String normalizedQuery, AnalysisParameters parameters, UUID organizationId, Instant since) {
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

    private static final class StubReports implements TrendReportRepository {
        @Override
        public Optional<TrendReport> findLatestForDirection(
                String normalizedQuery, String paramsDiscriminator, ResearchRequestId excludingRequestId) {
            return Optional.empty();
        }

        @Override
        public int nextVersionFor(String normalizedQuery, String paramsDiscriminator) {
            return 1;
        }

        private final TrendReport report;

        private StubReports(TrendReport report) {
            this.report = report;
        }

        @Override
        public Optional<TrendReport> findById(TrendReportId id) {
            return id.equals(report.id()) ? Optional.of(report) : Optional.empty();
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
}
