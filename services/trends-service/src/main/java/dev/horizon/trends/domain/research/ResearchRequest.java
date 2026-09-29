package dev.horizon.trends.domain.research;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import dev.horizon.platform.common.error.HorizonException;
import dev.horizon.platform.common.id.Uuid7;
import dev.horizon.platform.common.util.Guards;
import dev.horizon.trends.domain.report.TrendReportId;
import dev.horizon.trends.domain.research.event.ResearchRequestCompleted;
import dev.horizon.trends.domain.research.event.ResearchRequestFailed;
import dev.horizon.trends.domain.research.event.ResearchRequestSubmitted;
import dev.horizon.trends.domain.shared.TrendsDomainEvent;

/**
 * Aggregate root for one analysis request — and the process manager's state (ADR-0004).
 *
 * <p>Every state change goes through a behaviour method that (a) validates the transition against
 * {@link ResearchStatus}, (b) mutates consistently, and (c) records the resulting fact. There are no
 * setters: an object of this class is always in a legal state, which is the whole point of an
 * aggregate.
 *
 * <p>Invariants (domain-model doc §4.1):
 *
 * <ul>
 *   <li>I1 only legal status transitions;
 *   <li>I2 parameters within allowed ranges (enforced by {@link AnalysisParameters});
 *   <li>I3 {@code COMPLETED ⟹ reportId != null};
 *   <li>I4 {@code FAILED ⟹ failure != null};
 *   <li>I5 progress never decreases within an attempt;
 *   <li>I6 terminal states are immutable.
 * </ul>
 */
public final class ResearchRequest {

    private final ResearchRequestId id;
    private final RequesterRef requester;
    private final TechnologyDomainQuery query;
    private final AnalysisParameters parameters;
    private final String idempotencyKey;
    private final Instant submittedAt;
    private final Instant deadlineAt;

    private ResearchStatus status;
    private AnalysisProgress progress;
    private int attempt;
    private UUID corpusSnapshotId;
    private CorpusCoverage corpusCoverage = CorpusCoverage.empty();
    private UUID analysisJobId;
    private TrendReportId reportId;
    private boolean partial;
    private FailureInfo failure;
    private Instant startedAt;
    private Instant finishedAt;
    private long version;

    private final transient List<TrendsDomainEvent> pendingEvents = new ArrayList<>();

    /** Full-state constructor used only by the persistence mapper when rehydrating. */
    @SuppressWarnings("java:S107") // A rehydration constructor legitimately mirrors the row.
    public ResearchRequest(
            ResearchRequestId id,
            RequesterRef requester,
            TechnologyDomainQuery query,
            AnalysisParameters parameters,
            String idempotencyKey,
            Instant submittedAt,
            Instant deadlineAt,
            ResearchStatus status,
            AnalysisProgress progress,
            int attempt,
            UUID corpusSnapshotId,
            CorpusCoverage corpusCoverage,
            UUID analysisJobId,
            TrendReportId reportId,
            boolean partial,
            FailureInfo failure,
            Instant startedAt,
            Instant finishedAt,
            long version) {
        this.id = Guards.requireNonNull(id, "id");
        this.requester = Guards.requireNonNull(requester, "requester");
        this.query = Guards.requireNonNull(query, "query");
        this.parameters = Guards.requireNonNull(parameters, "parameters");
        this.idempotencyKey = idempotencyKey;
        this.submittedAt = Guards.requireNonNull(submittedAt, "submittedAt");
        this.deadlineAt = Guards.requireNonNull(deadlineAt, "deadlineAt");
        this.status = Guards.requireNonNull(status, "status");
        this.progress = Guards.requireNonNull(progress, "progress");
        this.attempt = attempt;
        this.corpusSnapshotId = corpusSnapshotId;
        this.corpusCoverage = corpusCoverage == null ? CorpusCoverage.empty() : corpusCoverage;
        this.analysisJobId = analysisJobId;
        this.reportId = reportId;
        this.partial = partial;
        this.failure = failure;
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.version = version;
        verifyInvariants();
    }

    /** Factory for a brand-new request. Records {@link ResearchRequestSubmitted}. */
    public static ResearchRequest submit(
            RequesterRef requester,
            TechnologyDomainQuery query,
            AnalysisParameters parameters,
            String idempotencyKey,
            Duration timeout,
            Instant now) {
        Guards.requireNonNull(timeout, "timeout");
        Guards.requireNonNull(now, "now");
        var request = new ResearchRequest(
                ResearchRequestId.generate(),
                requester,
                query,
                parameters,
                idempotencyKey,
                now,
                now.plus(timeout),
                ResearchStatus.PENDING,
                AnalysisProgress.queued(now),
                1,
                null,
                CorpusCoverage.empty(),
                null,
                null,
                false,
                null,
                null,
                null,
                0L);
        request.record(new ResearchRequestSubmitted(
                Uuid7.randomUuid7(), now, request.id, requester, query, parameters, request.attempt));
        return request;
    }

    // ── Saga transitions ──────────────────────────────────────────────────────────────────────

    public void startCollecting(Instant now) {
        transitionTo(ResearchStatus.COLLECTING, now);
        if (startedAt == null) {
            startedAt = now;
        }
        progress = progress.advanceTo(
                AnalysisStage.COLLECTING,
                AnalysisStage.COLLECTING.startPercent(),
                "Сбор публикаций, патентов и репозиториев",
                now);
    }

    /**
     * The corpus is ready; move on to analysis.
     *
     * <p>When {@code coverage} reports unavailable sources the request still succeeds, but the
     * resulting report is flagged as incomplete (BR-C7/BRULE-8).
     */
    public void corpusCollected(UUID snapshotId, CorpusCoverage coverage, Instant now) {
        Guards.requireNonNull(snapshotId, "snapshotId");
        Guards.requireNonNull(coverage, "coverage");
        int documentCount = coverage.documentCount();
        if (documentCount <= 0) {
            fail(FailureInfo.noDocuments(), now);
            return;
        }
        transitionTo(ResearchStatus.ANALYZING, now);
        this.corpusSnapshotId = snapshotId;
        this.corpusCoverage = coverage;
        this.partial = this.partial || coverage.partial();
        progress = progress.advanceTo(
                AnalysisStage.ANALYZING,
                AnalysisStage.ANALYZING.startPercent(),
                "Собрано документов: %d. Выделение технологических тем".formatted(documentCount),
                now);
    }

    /** Progress reported by ingestion while it collects (0..100 within the stage). */
    public void reportCollectionProgress(int withinStagePercent, String message, Instant now) {
        if (status != ResearchStatus.COLLECTING) {
            return; // the corpus is already collected; a late tick must not rewrite the analysis message
        }
        int percent = AnalysisStage.COLLECTING.globalPercent(withinStagePercent);
        if (percent < progress.percent()) {
            return; // an older tick delivered late: its message ("15 of 21") would read as going backwards
        }
        progress = progress.advanceTo(
                AnalysisStage.COLLECTING,
                percent,
                message == null || message.isBlank() ? progress.message() : message,
                now);
    }

    /** Progress reported by the analytics engine while it works (0..100 within the stage). */
    public void reportAnalysisProgress(int withinStagePercent, String message, Instant now) {
        if (status != ResearchStatus.ANALYZING) {
            return; // late progress for an already-advanced request is simply ignored
        }
        progress = progress.advanceTo(
                AnalysisStage.ANALYZING, AnalysisStage.ANALYZING.globalPercent(withinStagePercent), message, now);
    }

    public void startAssembling(UUID jobId, Instant now) {
        transitionTo(ResearchStatus.ASSEMBLING, now);
        this.analysisJobId = jobId;
        progress = progress.advanceTo(
                AnalysisStage.ASSEMBLING, AnalysisStage.ASSEMBLING.startPercent(), "Формирование отчёта", now);
    }

    public void complete(TrendReportId report, int trendCount, Instant now) {
        Guards.requireNonNull(report, "reportId");
        transitionTo(ResearchStatus.COMPLETED, now);
        this.reportId = report;
        this.finishedAt = now;
        this.progress = progress.completed(now);
        long durationMs = Duration.between(submittedAt, now).toMillis();
        record(new ResearchRequestCompleted(
                Uuid7.randomUuid7(), now, id, report, requester, query.normalized(), trendCount, partial, durationMs));
    }

    public void fail(FailureInfo info, Instant now) {
        Guards.requireNonNull(info, "failure");
        if (status.isTerminal()) {
            return; // failing an already-finished request is a no-op, not an error
        }
        this.status = ResearchStatus.FAILED;
        this.failure = info;
        this.finishedAt = now;
        this.progress = progress.advanceTo(AnalysisStage.DONE, progress.percent(), info.message(), now);
        record(new ResearchRequestFailed(Uuid7.randomUuid7(), now, id, attempt, info));
    }

    /**
     * Returns a request failed by the saga deadline to the running state so a late result can still
     * be accepted.
     *
     * <p>The deadline sweep is a promise about how long the aggregate waits, not an instruction to
     * the engine: nothing cancels the analysis when the request is marked failed, so the work keeps
     * going and the answer does arrive — just after everyone stopped listening. Dropping it costs
     * the full price of having computed it and leaves the analyst looking at a failure while the
     * report it describes exists in every respect except being written down.
     *
     * <p>Only {@link FailureInfo#SAGA_TIMEOUT} reopens. A request cancelled by its owner, or failed
     * because a step genuinely broke, is finished for a reason that a late message does not revise.
     *
     * <p>The failure event has already been published by then and is not retracted: it was true when
     * it was recorded. Completion simply follows it.
     *
     * @return whether the request was reopened
     */
    public boolean reopenAfterTimeout(Instant now) {
        if (status != ResearchStatus.FAILED || failure == null || !FailureInfo.SAGA_TIMEOUT.equals(failure.code())) {
            return false;
        }
        this.status = ResearchStatus.ANALYZING;
        this.failure = null;
        this.finishedAt = null;
        this.progress = progress.advanceTo(
                AnalysisStage.ANALYZING, progress.percent(), "Результат анализа получен, формируется отчёт", now);
        return true;
    }

    public void cancel(Instant now) {
        if (status.isTerminal()) {
            throw HorizonException.illegalTransition("Запрос уже завершён и не может быть отменён");
        }
        transitionTo(ResearchStatus.CANCELLED, now);
        this.finishedAt = now;
        this.progress = progress.advanceTo(AnalysisStage.DONE, progress.percent(), "Запрос отменён пользователем", now);
    }

    /** True when the saga deadline has passed and the request is still running (FR-02.5). */
    public boolean isOverdue(Instant now) {
        return status.isActive() && now.isAfter(deadlineAt);
    }

    /**
     * Guards changes to the request, which is a narrower right than reading it.
     *
     * <p>Visibility widened to the organisation; the ability to cancel did not. A colleague reading a
     * report is the point of BR-A42, but a colleague cancelling an analysis someone else's quota paid
     * for is a different act — and the two were about to share one check.
     */
    public boolean isOwnedBy(ReportViewer viewer) {
        return viewer.administrator() || requester.userId().equals(viewer.userId());
    }

    /**
     * Guards access (BR-A42).
     *
     * <p>The organisation is the unit that ordered the research and the unit whose hourly quota paid
     * for it, so it is also the unit that reads it. Owner-only visibility turned a shared portfolio
     * into a set of private ones — and, worse, the cache handed a colleague an identifier for a
     * report it would then refuse to show.
     */
    public boolean isVisibleTo(ReportViewer viewer) {
        return viewer.administrator()
                || requester.userId().equals(viewer.userId())
                || viewer.sharesOrganizationWith(requester);
    }

    private void transitionTo(ResearchStatus next, Instant now) {
        Guards.requireNonNull(now, "now");
        if (status == next) {
            return; // idempotent: duplicate delivery of the same saga event must be harmless
        }
        if (!status.canTransitionTo(next)) {
            throw HorizonException.illegalTransition(
                    "Недопустимый переход состояния %s → %s для запроса %s".formatted(status, next, id));
        }
        this.status = next;
    }

    private void verifyInvariants() {
        if (status == ResearchStatus.COMPLETED && reportId == null) {
            throw new IllegalStateException("I3 нарушен: завершённый запрос обязан ссылаться на отчёт");
        }
        if (status == ResearchStatus.FAILED && failure == null) {
            throw new IllegalStateException("I4 нарушен: неуспешный запрос обязан содержать причину");
        }
    }

    private void record(TrendsDomainEvent event) {
        pendingEvents.add(event);
    }

    /** Drains recorded events for publication; called once by the application layer per transaction. */
    public List<TrendsDomainEvent> drainEvents() {
        var drained = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return drained;
    }

    public List<TrendsDomainEvent> peekEvents() {
        return Collections.unmodifiableList(pendingEvents);
    }

    // ── Accessors ─────────────────────────────────────────────────────────────────────────────

    public ResearchRequestId id() {
        return id;
    }

    public RequesterRef requester() {
        return requester;
    }

    public TechnologyDomainQuery query() {
        return query;
    }

    public AnalysisParameters parameters() {
        return parameters;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public Instant submittedAt() {
        return submittedAt;
    }

    public Instant deadlineAt() {
        return deadlineAt;
    }

    public ResearchStatus status() {
        return status;
    }

    public AnalysisProgress progress() {
        return progress;
    }

    public int attempt() {
        return attempt;
    }

    public Optional<UUID> corpusSnapshotId() {
        return Optional.ofNullable(corpusSnapshotId);
    }

    public CorpusCoverage corpusCoverage() {
        return corpusCoverage;
    }

    public Optional<UUID> analysisJobId() {
        return Optional.ofNullable(analysisJobId);
    }

    public Optional<TrendReportId> reportId() {
        return Optional.ofNullable(reportId);
    }

    public boolean partial() {
        return partial;
    }

    public Optional<FailureInfo> failure() {
        return Optional.ofNullable(failure);
    }

    public Optional<Instant> startedAt() {
        return Optional.ofNullable(startedAt);
    }

    public Optional<Instant> finishedAt() {
        return Optional.ofNullable(finishedAt);
    }

    public long version() {
        return version;
    }

    /**
     * Estimated seconds remaining, derived from elapsed time and the progress fraction.
     *
     * <p>The naive extrapolation {@code elapsed x (100 - p) / p} is only meaningful while {@code p}
     * actually moves. Two guards keep it from producing absurd numbers:
     *
     * <ul>
     *   <li>progress parked on a stage's lower bound carries no information about that stage's
     *       length — at {@code p = 5} the multiplier is 19, so every second of waiting adds
     *       nineteen seconds to the estimate. No estimate beats a growing fiction;
     *   <li>the saga deadline is the largest remainder that can still happen, so it caps the
     *       result. Without it the estimate is unbounded above.
     * </ul>
     */
    public Optional<Long> etaSeconds(Instant now) {
        if (status.isTerminal() || startedAt == null || progress.percent() <= 0) {
            return Optional.empty();
        }
        if (progress.percent() <= progress.stage().startPercent()) {
            return Optional.empty();
        }
        long elapsed = Duration.between(startedAt, now).toSeconds();
        if (elapsed <= 0) {
            return Optional.empty();
        }
        long total = Math.round(elapsed * 100.0 / progress.percent());
        long remaining = Math.max(0, total - elapsed);
        long untilDeadline = Math.max(0, Duration.between(now, deadlineAt).toSeconds());
        return Optional.of(Math.min(remaining, untilDeadline));
    }
}
