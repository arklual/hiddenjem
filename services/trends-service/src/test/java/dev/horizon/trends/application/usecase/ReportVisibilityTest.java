package dev.horizon.trends.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import dev.horizon.platform.common.error.HorizonException;
import dev.horizon.platform.common.event.DomainEventPublisher;
import dev.horizon.trends.application.port.PageResult;
import dev.horizon.trends.application.port.ReportCache;
import dev.horizon.trends.application.port.ResearchRequestRepository;
import dev.horizon.trends.application.port.TrendFeedbackRepository;
import dev.horizon.trends.application.port.TrendReportRepository;
import dev.horizon.trends.domain.feedback.TrendFeedback;
import dev.horizon.trends.domain.report.TrendReport;
import dev.horizon.trends.domain.report.TrendReportId;
import dev.horizon.trends.domain.research.AnalysisParameters;
import dev.horizon.trends.domain.research.ReportViewer;
import dev.horizon.trends.domain.research.ResearchRequest;
import dev.horizon.trends.domain.research.ResearchRequestId;
import dev.horizon.trends.domain.research.ResearchStatus;
import dev.horizon.trends.support.Fixtures;

/**
 * A report is exactly as visible as the request that produced it — on every path that touches it.
 *
 * <p>Authentication is fail-closed at the filter chain, so the risk that remains is object-level: a
 * perfectly valid analyst token reaching another team's report. That check lives in one place by
 * design, and the way it fails is by a new path forgetting to go through it — which is precisely
 * what happened to trend feedback, and what these tests exist to catch the next time.
 */
class ReportVisibilityTest {

    private static final UUID STRANGER = UUID.fromString("99999999-9999-4999-8999-999999999999");

    private ResearchRequest request;
    private TrendReport report;
    private GetTrendReportUseCase reports;
    private RecordingFeedback feedback;
    private RecordTrendFeedbackUseCase recordFeedback;

    @BeforeEach
    void setUp() {
        request = Fixtures.assemblingRequest();
        report = Fixtures.report(request, 3);
        var requests = new StubRequests(request);
        reports = new GetTrendReportUseCase(new StubReports(report), requests, new NoCache());
        feedback = new RecordingFeedback();
        recordFeedback = new RecordTrendFeedbackUseCase(
                feedback,
                reports,
                new NoopEvents(),
                Clock.fixed(Instant.parse("2026-03-01T10:00:00Z"), ZoneOffset.UTC));
    }

    private String anyTrendKey() {
        return report.trends().getFirst().trendKey();
    }

    @Test
    void theOwnerCanReadTheirOwnReport() {
        assertThat(reports.get(report.id(), ReportViewer.of(Fixtures.USER_ID))).isEqualTo(report);
    }

    @Test
    void aStrangerCannotReadIt() {
        assertThatThrownBy(() -> reports.get(report.id(), ReportViewer.of(STRANGER)))
                .isInstanceOf(HorizonException.class);
    }

    @Test
    void aStrangerCannotRecordFeedbackOnIt() {
        // The gap this suite was written for. Without the check the call succeeded outright: a
        // stranger could attach a verdict to another team's finding.
        assertThatThrownBy(() -> recordFeedback.record(
                        ReportViewer.of(STRANGER), report.id(), anyTrendKey(), TrendFeedback.Verdict.RELEVANT, null))
                .isInstanceOf(HorizonException.class);

        assertThat(feedback.saved).isEmpty();
    }

    @Test
    void aStrangerCannotTellARealReportFromAMissingOneThroughFeedback() {
        // Enumeration, not just writing, is the reason this matters. If a foreign report answered
        // differently from a nonexistent one, an analyst could walk the id space and learn which
        // reports exist without ever being allowed to open one.
        var missing = new TrendReportId(UUID.fromString("00000000-0000-4000-8000-000000000000"));

        var onForeign = refusalKind(() -> recordFeedback.record(
                ReportViewer.of(STRANGER), report.id(), anyTrendKey(), TrendFeedback.Verdict.RELEVANT, null));
        var onMissing = refusalKind(() -> recordFeedback.record(
                ReportViewer.of(STRANGER), missing, "whatever", TrendFeedback.Verdict.RELEVANT, null));

        assertThat(onForeign).isEqualTo(onMissing);
    }

    @Test
    void aStrangerCannotProbeTrendKeysThroughFeedback() {
        // Same leak one level down: a present key must not be distinguishable from an absent one
        // when the caller may not read the report at all.
        var present = refusalKind(() -> recordFeedback.record(
                ReportViewer.of(STRANGER), report.id(), anyTrendKey(), TrendFeedback.Verdict.RELEVANT, null));
        var absent = refusalKind(() -> recordFeedback.record(
                ReportViewer.of(STRANGER),
                report.id(),
                "definitely-not-a-trend",
                TrendFeedback.Verdict.RELEVANT,
                null));

        assertThat(present).isEqualTo(absent);
    }

    @Test
    void theOwnerCanStillRecordFeedback() {
        // A visibility check that also blocked the owner would be a different bug with the same
        // green tests, so the positive case is pinned too.
        recordFeedback.record(
                ReportViewer.of(Fixtures.USER_ID), report.id(), anyTrendKey(), TrendFeedback.Verdict.RELEVANT, null);

        assertThat(feedback.saved).hasSize(1);
    }

    @Test
    void anAdministratorIsNotBlocked() {
        assertThat(reports.get(report.id(), new ReportViewer(STRANGER, null, true)))
                .isEqualTo(report);
    }

    /**
     * The kind of refusal, without the identifier echoed back to the caller.
     *
     * <p>The id in the message is the one the caller supplied, so it carries nothing they did not
     * already know; the problem type is what could distinguish "exists but is not yours" from "does
     * not exist", and that is what must match.
     */
    private static String refusalKind(Runnable call) {
        try {
            call.run();
            return "no-exception";
        } catch (HorizonException e) {
            return e.type().slug();
        }
    }

    // ───────────────────────────── stubs ─────────────────────────────

    private static final class RecordingFeedback implements TrendFeedbackRepository {
        private final List<TrendFeedback> saved = new ArrayList<>();

        @Override
        public TrendFeedback save(TrendFeedback entry) {
            saved.add(entry);
            return entry;
        }

        @Override
        public Optional<TrendFeedback> find(UUID userId, TrendReportId reportId, String trendKey) {
            return Optional.empty();
        }

        @Override
        public List<TrendFeedback> findCarried(UUID userId, String normalizedQuery, TrendReportId excludingReportId) {
            return List.of();
        }

        @Override
        public int deleteByDirection(UUID userId, String normalizedQuery, String trendKey) {
            return 0;
        }

        @Override
        public List<TrendFeedback> findByDirection(UUID userId, String normalizedQuery) {
            return List.of();
        }

        @Override
        public List<TrendFeedback> findByUserAndReport(UUID userId, TrendReportId reportId) {
            return List.copyOf(saved);
        }
    }

    private static final class NoopEvents implements DomainEventPublisher {
        @Override
        public void publish(java.util.Collection<? extends dev.horizon.platform.common.event.DomainEvent> events) {}
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

    private record StubRequests(ResearchRequest request) implements ResearchRequestRepository {

        @Override
        public Optional<ResearchRequest> findById(ResearchRequestId id) {
            return id.equals(request.id()) ? Optional.of(request) : Optional.empty();
        }

        @Override
        public Optional<ResearchRequest> findByIdForUpdate(ResearchRequestId id) {
            return findById(id);
        }

        @Override
        public ResearchRequest save(ResearchRequest request) {
            return request;
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
                String normalizedQuery,
                dev.horizon.trends.domain.research.AnalysisParameters parameters,
                UUID organizationId,
                Instant since) {
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
}
