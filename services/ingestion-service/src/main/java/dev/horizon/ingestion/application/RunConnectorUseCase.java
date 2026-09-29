package dev.horizon.ingestion.application;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import dev.horizon.ingestion.config.IngestionProperties;
import dev.horizon.ingestion.domain.port.CollectionRequest;
import dev.horizon.ingestion.domain.port.DistributedLock;
import dev.horizon.ingestion.domain.port.IngestionRunRepository;
import dev.horizon.ingestion.domain.port.NormalizingSourceConnector;
import dev.horizon.ingestion.domain.port.SourceCursorRepository;
import dev.horizon.ingestion.domain.port.SourceRepository;
import dev.horizon.ingestion.domain.run.Cursor;
import dev.horizon.ingestion.domain.run.IngestionRun;
import dev.horizon.ingestion.domain.run.RunMode;
import dev.horizon.ingestion.domain.source.Source;
import dev.horizon.platform.common.error.HorizonException;
import dev.horizon.platform.common.error.ProblemType;

/**
 * Runs one connector — on a schedule (UC-16) or on demand from {@code POST
 * /api/v1/sources/{id}/runs}.
 *
 * <p>The manual path answers {@code 202 Accepted} with a real run id and continues on a worker
 * thread: a crawl takes minutes, and holding an HTTP connection for it would be a denial of service
 * against ourselves.
 *
 * <p>The scheduled path takes a Redis lock per connector so that N replicas do not crawl the same
 * source N times. <b>The lock is an optimisation, not a correctness mechanism</b> — ingestion is
 * idempotent by {@code (sourceId, externalId)} and {@code dedupKey}, so losing the lock service
 * degrades politeness, never data.
 */
@Service
public class RunConnectorUseCase {

    private static final Logger log = LoggerFactory.getLogger(RunConnectorUseCase.class);

    private final ConnectorCatalog catalog;
    private final ConnectorCollectionService collectionService;
    private final SourceRepository sources;
    private final IngestionRunRepository runs;
    private final SourceCursorRepository cursors;
    private final DistributedLock lock;
    private final IngestionProperties properties;
    private final Executor executor;
    private final Clock clock;

    public RunConnectorUseCase(
            ConnectorCatalog catalog,
            ConnectorCollectionService collectionService,
            SourceRepository sources,
            IngestionRunRepository runs,
            SourceCursorRepository cursors,
            DistributedLock lock,
            IngestionProperties properties,
            @Qualifier("applicationTaskExecutor") Executor executor,
            Clock clock) {
        this.catalog = catalog;
        this.collectionService = collectionService;
        this.sources = sources;
        this.runs = runs;
        this.cursors = cursors;
        this.lock = lock;
        this.properties = properties;
        this.executor = executor;
        this.clock = clock;
    }

    /**
     * Starts a run and returns immediately.
     *
     * @throws HorizonException {@code not-found} when the source is unknown, {@code conflict} when a
     *     run is already in flight, {@code upstream-unavailable} when the source is disabled
     */
    public IngestionRun trigger(String sourceId, RunMode mode, String query, LocalDate from, LocalDate to) {
        Source source = sources.findById(sourceId).orElseThrow(() -> HorizonException.notFound("Источник", sourceId));
        if (!source.isEnabled()) {
            throw new HorizonException(ProblemType.UPSTREAM_UNAVAILABLE, "Источник %s выключен".formatted(sourceId));
        }
        NormalizingSourceConnector connector =
                catalog.byId(sourceId).orElseThrow(() -> HorizonException.notFound("Коннектор", sourceId));
        if (runs.existsRunning(sourceId, clock.instant().minus(properties.staleRunAfter()))) {
            throw HorizonException.conflict("Прогон по источнику %s уже выполняется".formatted(sourceId));
        }

        CollectionRequest request = buildRequest(source, query, from, to);
        IngestionRun run = collectionService.startRun(connector, request, mode);
        executor.execute(() -> {
            try {
                collectionService.execute(connector, request, run, true);
            } catch (RuntimeException e) {
                // execute() already records failures on the run; this is the last-resort net.
                log.error("Unhandled failure in ingestion run {}", run.id(), e);
            }
        });
        return run;
    }

    /**
     * Scheduled incremental pass over every enabled connector.
     *
     * <p>Runs sources sequentially and independently: one unavailable source has no effect on the
     * others.
     */
    public void runIncrementalForAllSources() {
        for (NormalizingSourceConnector connector : catalog.all()) {
            String sourceId = connector.descriptor().id();
            try {
                sources.findById(sourceId)
                        .filter(Source::isEnabled)
                        .ifPresent(source -> runIncremental(connector, source));
            } catch (RuntimeException e) {
                log.warn("Scheduled ingestion for {} failed", sourceId, e);
            }
        }
    }

    private void runIncremental(NormalizingSourceConnector connector, Source source) {
        String key = "lock:connector:" + source.id();
        boolean executed = lock.runIfAcquired(key, properties.lockLease(), () -> {
            if (runs.existsRunning(source.id(), clock.instant().minus(properties.staleRunAfter()))) {
                log.debug("Skipping scheduled run for {}: another run is in flight", source.id());
                return;
            }
            LocalDate to = LocalDate.ofInstant(clock.instant(), java.time.ZoneOffset.UTC);
            LocalDate horizon = to.minusDays(properties.incrementalWindowDays());
            // Genuinely incremental: start at the watermark when there is one, but never look back
            // further than the configured horizon — after a long outage, re-reading years of history
            // in one pass would blow the rate-limit budget for the day.
            LocalDate watermark = cursors.find(source.id())
                    .flatMap(Cursor::lastPublishedOnOrEmpty)
                    .orElse(horizon);
            LocalDate from = watermark.isAfter(horizon) ? watermark : horizon;
            CollectionRequest request = new CollectionRequest(
                    null, "*", "*", null, from, to, Set.of(), properties.scheduledMaxDocuments(), List.of());
            if (!connector.supports(request) || !connector.descriptor().available()) {
                log.debug("Skipping scheduled run for {}: connector declined", source.id());
                return;
            }
            IngestionRun run = collectionService.startRun(connector, request, RunMode.INCREMENTAL);
            collectionService.execute(connector, request, run, true);
        });
        if (!executed) {
            log.debug("Another replica holds the ingestion lock for {}", source.id());
        }
    }

    private CollectionRequest buildRequest(Source source, String query, LocalDate from, LocalDate to) {
        LocalDate today = LocalDate.ofInstant(clock.instant(), java.time.ZoneOffset.UTC);
        LocalDate windowTo = to == null ? today : to;
        LocalDate windowFrom = from == null ? windowTo.minusDays(properties.incrementalWindowDays()) : from;
        String effectiveQuery = query == null || query.isBlank() ? "*" : query;
        // No class filter: the operator picked the source explicitly, so filtering by class again
        // could only exclude documents they asked for (a mixed-class source such as the fixture
        // corpus would lose most of its content).
        return new CollectionRequest(
                null,
                effectiveQuery,
                effectiveQuery,
                null,
                windowFrom,
                windowTo,
                Set.of(),
                properties.scheduledMaxDocuments(),
                List.of());
    }

    /** Exposed for the scheduler's structured log line. */
    public List<String> connectorIds() {
        return catalog.all().stream()
                .map(connector -> connector.descriptor().id())
                .toList();
    }
}
