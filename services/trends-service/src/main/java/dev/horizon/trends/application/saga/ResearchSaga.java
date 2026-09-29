package dev.horizon.trends.application.saga;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.horizon.platform.common.error.HorizonException;
import dev.horizon.platform.common.event.DomainEventPublisher;
import dev.horizon.trends.application.dto.AnalysisResult;
import dev.horizon.trends.application.port.CommandSender;
import dev.horizon.trends.application.port.MethodologyProfileRepository;
import dev.horizon.trends.application.port.ProgressBroadcaster;
import dev.horizon.trends.application.port.ReportCache;
import dev.horizon.trends.application.port.ResearchMetrics;
import dev.horizon.trends.application.port.ResearchRequestRepository;
import dev.horizon.trends.application.port.SavedDomainRepository;
import dev.horizon.trends.application.port.TrendFeedbackRepository;
import dev.horizon.trends.application.port.TrendReportRepository;
import dev.horizon.trends.application.port.UnrecognizedDirectionJournal;
import dev.horizon.trends.application.usecase.ReportAssembler;
import dev.horizon.trends.domain.direction.UnrecognizedDirection;
import dev.horizon.trends.domain.feedback.TrendFeedback;
import dev.horizon.trends.domain.methodology.MethodologyProfile;
import dev.horizon.trends.domain.report.TrendReport;
import dev.horizon.trends.domain.report.TrendReportId;
import dev.horizon.trends.domain.research.AnalysisEngine;
import dev.horizon.trends.domain.research.CorpusCoverage;
import dev.horizon.trends.domain.research.FailureInfo;
import dev.horizon.trends.domain.research.ResearchRequest;
import dev.horizon.trends.domain.research.ResearchRequestId;
import dev.horizon.trends.domain.shared.Topics;

/**
 * Process manager driving one analysis from submission to report (ADR-0004).
 *
 * <p>Every handler follows the same shape: load the request **for update**, apply the domain
 * transition, persist, publish whatever the aggregate recorded, then broadcast progress. Loading for
 * update is what makes concurrent saga steps safe — without the row lock, a late progress event and
 * a completion event could interleave and lose a transition.
 *
 * <p>Handlers are also **idempotent by construction**: the aggregate treats a transition to its
 * current state as a no-op, so redelivery (which at-least-once messaging guarantees will happen)
 * changes nothing. Duplicate suppression at the messaging layer is an optimisation, not the
 * correctness mechanism.
 *
 * <p>Compensation: there is none, deliberately. The only side effect before the report exists is
 * collected documents, which are useful, idempotent and reusable by the next attempt — deleting them
 * would be strictly harmful (ADR-0004).
 */
@Service
public class ResearchSaga {

    private static final Logger log = LoggerFactory.getLogger(ResearchSaga.class);

    private final ResearchRequestRepository requests;
    private final TrendReportRepository reports;
    private final MethodologyProfileRepository profiles;
    private final ReportAssembler assembler;
    private final DomainEventPublisher events;
    private final CommandSender commands;
    private final ProgressBroadcaster progress;
    private final ReportCache cache;
    private final SavedDomainRepository savedDomains;
    private final UnrecognizedDirectionJournal unrecognizedDirections;
    private final TrendFeedbackRepository feedback;
    private final ResearchMetrics metrics;
    private final Clock clock;

    public ResearchSaga(
            ResearchRequestRepository requests,
            TrendReportRepository reports,
            MethodologyProfileRepository profiles,
            ReportAssembler assembler,
            DomainEventPublisher events,
            CommandSender commands,
            ProgressBroadcaster progress,
            ReportCache cache,
            SavedDomainRepository savedDomains,
            UnrecognizedDirectionJournal unrecognizedDirections,
            TrendFeedbackRepository feedback,
            ResearchMetrics metrics,
            Clock clock) {
        this.requests = requests;
        this.reports = reports;
        this.profiles = profiles;
        this.assembler = assembler;
        this.events = events;
        this.commands = commands;
        this.progress = progress;
        this.cache = cache;
        this.savedDomains = savedDomains;
        this.unrecognizedDirections = unrecognizedDirections;
        this.feedback = feedback;
        this.metrics = metrics;
        this.clock = clock;
    }

    /** Step 1 → 2: the corpus is ready, ask the analytics engine to work on it. */
    @Transactional
    public void onCorpusCollected(
            ResearchRequestId requestId,
            UUID snapshotId,
            int documentCount,
            List<String> sourcesUsed,
            List<String> unavailableSources,
            String causedBy) {

        withRequest(requestId, request -> {
            var now = clock.instant();
            request.corpusCollected(
                    snapshotId, new CorpusCoverage(documentCount, sourcesUsed, unavailableSources), now);
            if (request.status().isTerminal()) {
                return null; // empty corpus already failed the request
            }
            var profile = profiles.findById(request.parameters().methodologyProfileId())
                    .orElseGet(profiles::requireDefault);
            commands.send(CommandSender.OutboundCommand.causedBy(
                    causedBy,
                    "horizon.analysis.AnalyzeDomain",
                    Topics.ANALYSIS_COMMANDS,
                    request.id().toString(),
                    analyzeCommandPayload(request, snapshotId, profile, now)));
            return null;
        });
    }

    /** Progress reported by ingestion mid-collection; purely informational. */
    @Transactional
    public void onCollectionProgressed(ResearchRequestId requestId, int percent, String message) {
        withRequest(requestId, request -> {
            request.reportCollectionProgress(percent, message, clock.instant());
            return null;
        });
    }

    /** Progress reported by the engine mid-analysis; purely informational. */
    @Transactional
    public void onAnalysisProgressed(ResearchRequestId requestId, int percent, String message) {
        withRequest(requestId, request -> {
            request.reportAnalysisProgress(percent, message, clock.instant());
            return null;
        });
    }

    /** Step 2 → 3: results arrived; assemble and persist the immutable report. */
    @Transactional
    public void onDomainAnalyzed(AnalysisResult result) {
        var requestId = ResearchRequestId.of(result.researchRequestId());
        // Единственный обработчик, принимающий сообщение для просроченного запроса: см. acceptLate.
        withRequest(
                requestId,
                request -> {
                    var now = clock.instant();
                    request.startAssembling(analysisJobId(result), now);
                    try {
                        // By direction, not by request: a request produces exactly one report, so a lineage
                        // keyed by request left every report at version 1 with no predecessor — and three
                        // shipped features that compare versions could never fire.
                        String direction = request.query().normalized();
                        String discriminator = request.parameters().cacheDiscriminator();
                        int version = reports.nextVersionFor(direction, discriminator);
                        var previous = reports.findLatestForDirection(direction, discriminator, request.id())
                                .map(dev.horizon.trends.domain.report.TrendReport::id)
                                .orElse(null);
                        var report = assembler.assemble(
                                request,
                                result,
                                request.corpusCoverage().sourcesUsed(),
                                request.corpusCoverage().unavailableSources(),
                                version,
                                previous,
                                now);
                        var saved = reports.save(report);
                        cache.put(saved);
                        events.publish(saved.drainEvents());
                        request.complete(saved.id(), saved.trends().size(), now);
                        metrics.reportPublished(saved.trends().size(), request.partial());
                        pointTrackedDirectionAt(request, saved.id());
                        journalUnrecognizedDirection(request, saved, now);
                        log.info(
                                "Отчёт {} сформирован для запроса {}: {} трендов",
                                saved.id(),
                                request.id(),
                                saved.trends().size());
                    } catch (HorizonException e) {
                        request.fail(new FailureInfo(FailureInfo.ASSEMBLY_FAILED, e.getMessage(), false), now);
                        metrics.requestFailed();
                    }
                    return null;
                },
                true);
    }

    /**
     * Record a direction the crosswalk lexicon does not know, so curation follows demand.
     *
     * <p>Failure here is swallowed on purpose. The journal exists for whoever extends the lexicon;
     * the report exists for the analyst who is waiting. Trading the second for the first would make
     * the product less reliable the more instrumentation it grows, which is the wrong direction for
     * both.
     */
    private void journalUnrecognizedDirection(ResearchRequest request, TrendReport report, Instant now) {
        if (report.coverage().directionRecognized()) {
            return;
        }
        try {
            unrecognizedDirections.record(UnrecognizedDirection.firstTime(
                    request.requester().organizationId(),
                    request.query().normalized(),
                    request.query().raw(),
                    report.coverage().directionSuggestions(),
                    now));
        } catch (RuntimeException e) {
            log.warn(
                    "Не удалось записать нераспознанное направление «{}»: {}",
                    request.query().normalized(),
                    e.toString());
        }
    }

    /**
     * Point the tracked direction, if there is one, at the report that has just been produced.
     *
     * <p>Without this the radar is a screen that never fills: every saved direction reports "nothing
     * has been analysed here yet" no matter how many analyses have run, because the pointer it reads
     * is only ever written at creation, where it is null.
     *
     * <p>In the same transaction as the report on purpose. The alternative — reacting to the report
     * event afterwards — would allow a window in which the direction points at nothing, and a failure
     * in that window leaves the radar permanently stale with no signal that it is. Here the pointer
     * and the report it names either both land or neither does.
     *
     * <p>Matched by the requester and the normalised query, which is how a direction is identified
     * everywhere else. A direction saved by another user, or with a different question, is not this
     * one — and no analysis run by someone else may move an analyst's own bookmark.
     */
    private void pointTrackedDirectionAt(ResearchRequest request, TrendReportId reportId) {
        savedDomains
                .findByUserAndQuery(
                        request.requester().userId(), request.query().normalized())
                .ifPresent(direction -> savedDomains.save(direction.withLastReport(reportId)));
    }

    /** Any step reported an unrecoverable failure. */
    @Transactional
    public void onStepFailed(ResearchRequestId requestId, String code, String message, boolean retryable) {
        withRequest(requestId, request -> {
            request.fail(new FailureInfo(code, message, retryable), clock.instant());
            metrics.requestFailed();
            return null;
        });
    }

    /**
     * Sweeps requests that blew their deadline (FR-02.5).
     *
     * <p>Runs in bounded batches so a large backlog cannot monopolise a transaction, and it is safe
     * to run on every replica: each row is locked and re-checked before being failed.
     */
    @Transactional
    public int failOverdue(int batchSize) {
        var now = clock.instant();
        var overdue = requests.findOverdue(now, batchSize);
        int failed = 0;
        for (var candidate : overdue) {
            var locked = requests.findByIdForUpdate(candidate.id()).orElse(null);
            if (locked == null || !locked.isOverdue(now)) {
                continue;
            }
            locked.fail(FailureInfo.timeout(), now);
            requests.save(locked);
            events.publish(locked.drainEvents());
            progress.broadcast(locked);
            failed++;
            log.warn("Запрос {} завершён по таймауту саги", locked.id());
        }
        return failed;
    }

    /**
     * Common handler skeleton: lock, mutate, save, publish, broadcast.
     *
     * <p>An unknown request id is logged and ignored rather than throwing: it means the message
     * outlived its aggregate (e.g. after a data reset), and endlessly redelivering it would block the
     * partition for every other saga sharing it.
     */
    private <T> Optional<T> withRequest(
            ResearchRequestId requestId, java.util.function.Function<ResearchRequest, T> action) {
        return withRequest(requestId, action, false);
    }

    /**
     * @param acceptLate whether a result arriving after the deadline sweep is still worth taking.
     *     Only the analysis result is: the sweep does not stop the engine, so the answer keeps being
     *     computed and does land, and refusing it discards work that is already paid for. Every other
     *     message is genuinely too late — a progress tick for a finished request tells nobody
     *     anything.
     */
    private <T> Optional<T> withRequest(
            ResearchRequestId requestId, java.util.function.Function<ResearchRequest, T> action, boolean acceptLate) {
        var request = requests.findByIdForUpdate(requestId).orElse(null);
        if (request == null) {
            log.warn("Получено сообщение саги для неизвестного запроса {} — пропущено", requestId);
            return Optional.empty();
        }
        if (request.status().isTerminal()) {
            if (!acceptLate || !request.reopenAfterTimeout(clock.instant())) {
                log.debug("Запрос {} уже в терминальном статусе {} — сообщение пропущено", requestId, request.status());
                return Optional.empty();
            }
            log.info("Запрос {} был закрыт по таймауту, но результат анализа доехал — собираем отчёт", requestId);
        }
        T result = action.apply(request);
        requests.save(request);
        events.publish(request.drainEvents());
        progress.broadcast(request);
        return Optional.ofNullable(result);
    }

    /**
     * Derives the analysis job identifier deterministically from the saga coordinates.
     *
     * <p>A random id here would be the one non-reproducible value in an otherwise fully
     * deterministic pipeline, and it would differ between a run and its replay — exactly what
     * ADR-0015 forbids. The engine's own job row is keyed by the same {@code (requestId, attempt)}
     * pair, so this value is also the correct join key when correlating the two sides.
     */
    private static UUID analysisJobId(AnalysisResult result) {
        return UUID.nameUUIDFromBytes(("analysis:" + result.researchRequestId() + ":" + result.attempt())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /**
     * Темы, которые аналитик уже пометил как «не технология» по этому же направлению.
     *
     * <p>Замером установлено, что метод их не отличает и отличить не сможет: «concrete source
     * passages», «retrieved passages», «stale documents» — грамматически правильные именные группы,
     * отличающиеся от имени технологии только смыслом. Балл эмерджентности даёт им 44.9–46.4 против
     * 45.7–55.7 у настоящих тем, распределения перекрываются полностью; термхуд ставит обрывок
     * «state space» выше всех настоящих тем. Подробности и числа — docs/01-analysis/
     * 30-boundary-filter-findings.md.
     *
     * <p>Человек отличает их с одного взгляда, и продукт этот взгляд уже записывал — но никуда не
     * передавал: ни один путь анализа обратную связь не читал. Аналитик вычёркивал тему, а
     * следующий прогон ставил её на то же место. Здесь пометка возвращается в конвейер.
     *
     * <p>Берутся только вердикты {@code NOISE}. {@code ALREADY_KNOWN} — не повод скрывать: тема
     * настоящая, и её отсутствие в отчёте было бы враньём; {@code RELEVANT} тем более.
     *
     * <p>Область — тот же аналитик и то же направление, как у переносимой разметки (BR-A32).
     * Скрывать тему у коллеги по чужой пометке нельзя: он не увидит ни темы, ни причины её
     * отсутствия, а «мы это не смотрели» и «кто-то счёл это шумом» — разные вещи.
     */
    private List<String> suppressedTrendKeys(ResearchRequest request) {
        return feedback
                .findByDirection(request.requester().userId(), request.query().normalized())
                .stream()
                .filter(item -> item.verdict() == TrendFeedback.Verdict.NOISE)
                .map(TrendFeedback::trendKey)
                .distinct()
                .sorted()
                .toList();
    }

    /** Payload shape of {@code contracts/schemas/analyze-domain.command.json}. */
    private AnalyzeDomainPayload analyzeCommandPayload(
            ResearchRequest request, UUID snapshotId, MethodologyProfile profile, java.time.Instant now) {
        var parameters = new AnalyzeDomainPayload.Parameters(
                request.parameters().topN(),
                request.parameters().yearsWindow(),
                request.parameters().sourceClasses().stream()
                        .map(Enum::name)
                        .sorted()
                        .toList(),
                request.parameters().minConfidence(),
                request.parameters().includeMature(),
                suppressedTrendKeys(request));
        var profilePayload = new AnalyzeDomainPayload.Profile(
                profile.id().toString(),
                profile.methodologyVersion(),
                profile.aggregator().name(),
                profile.weights(),
                profile.parameters(),
                profile.confidenceThreshold());
        return new AnalyzeDomainPayload(
                request.id().toString(),
                request.attempt(),
                snapshotId.toString(),
                request.query().raw(),
                request.query().normalized(),
                // Движок один, и называется явно, а не опускается: команда, в которой написано, чем
                // считать, не зависит от того, какое умолчание окажется у движка, принявшего её.
                AnalysisEngine.CURRENT.wireName(),
                parameters,
                profilePayload);
    }

    public record AnalyzeDomainPayload(
            String researchRequestId,
            int attempt,
            String snapshotId,
            String query,
            String normalizedQuery,
            String engine,
            Parameters parameters,
            Profile profile) {

        public record Parameters(
                int topN,
                int yearsWindow,
                List<String> sourceClasses,
                double minConfidence,
                boolean includeMature,
                List<String> suppressedTrendKeys) {}

        public record Profile(
                String profileId,
                String methodologyVersion,
                String aggregator,
                Map<String, Double> weights,
                Map<String, Object> parameters,
                double confidenceThreshold) {}
    }
}
