package dev.horizon.ingestion.application;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import dev.horizon.ingestion.config.IngestionProperties;
import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.port.CollectionRequest;
import dev.horizon.ingestion.domain.port.DocumentStream;
import dev.horizon.ingestion.domain.port.IngestionRunRepository;
import dev.horizon.ingestion.domain.port.NormalizingSourceConnector;
import dev.horizon.ingestion.domain.port.SourceCursorRepository;
import dev.horizon.ingestion.domain.run.Cursor;
import dev.horizon.ingestion.domain.run.IngestionRun;
import dev.horizon.ingestion.domain.run.RunCounters;
import dev.horizon.ingestion.domain.run.RunMode;
import dev.horizon.platform.common.id.Uuid7;
import dev.horizon.platform.spring.web.TraceIds;

/**
 * Executes one connector against one request: page in, page committed, cursor advanced, counters
 * updated — repeat.
 *
 * <p>Two guarantees are implemented here and nowhere else.
 *
 * <p><b>Partial-failure tolerance (BR-C7, BRULE-8).</b> Any failure of a single connector is caught,
 * recorded on its {@link IngestionRun} and returned as a {@link CollectionOutcome}; it never
 * propagates to the caller, because one dead source must not cancel a collection that four other
 * sources are happily serving.
 *
 * <p><b>The cursor never runs ahead of committed data (FR-04.7).</b> The persisted cursor is written
 * only after {@link DocumentBatchWriter#write} has committed the page it belongs to, so a crash can
 * only ever cause a re-read — which deduplication absorbs — and never a skipped page.
 */
@Service
public class ConnectorCollectionService {

    private static final Logger log = LoggerFactory.getLogger(ConnectorCollectionService.class);

    private final DocumentBatchWriter batchWriter;
    private final IngestionRunRepository runs;
    private final SourceCursorRepository cursors;
    private final IngestionProperties properties;
    private final Uuid7 uuid7;
    private final Clock clock;

    public ConnectorCollectionService(
            DocumentBatchWriter batchWriter,
            IngestionRunRepository runs,
            SourceCursorRepository cursors,
            IngestionProperties properties,
            Uuid7 uuid7,
            Clock clock) {
        this.batchWriter = batchWriter;
        this.runs = runs;
        this.cursors = cursors;
        this.properties = properties;
        this.uuid7 = uuid7;
        this.clock = clock;
    }

    /**
     * Creates and persists a {@code RUNNING} run for the connector.
     *
     * <p>Split from {@link #execute} so that the manual trigger endpoint can answer {@code 202} with
     * a real run id while the crawl proceeds on another thread.
     */
    public IngestionRun startRun(NormalizingSourceConnector connector, CollectionRequest request, RunMode mode) {
        String sourceId = connector.descriptor().id();
        // Only the watermark survives between runs. An opaque page token (OpenAlex, Crossref) or an
        // offset (arXiv, GitHub) is a position inside the result set of one specific query; the next
        // run asks a different question, so reusing the token would resume in the wrong place — or,
        // worse, past the end, and quietly collect nothing. The publication-date watermark is the
        // only piece of cursor state that means the same thing across runs.
        Cursor cursorBefore = mode == RunMode.INCREMENTAL
                ? cursors.find(sourceId)
                        .map(persisted -> Cursor.ofDate(persisted.lastPublishedOn()))
                        .orElseGet(Cursor::start)
                : Cursor.start();
        IngestionRun run = IngestionRun.start(
                uuid7.next(),
                sourceId,
                mode,
                request.researchRequestId(),
                request.query(),
                request.windowFrom(),
                request.windowTo(),
                cursorBefore,
                clock.instant(),
                TraceIds.current());
        return runs.save(run);
    }

    /**
     * Drives the connector to completion.
     *
     * @param persistCursor whether the source cursor should be advanced — true for scheduled and
     *     backfill crawls, false for a one-off collection serving a research request, which must not
     *     move the shared incremental watermark
     */
    public CollectionOutcome execute(
            NormalizingSourceConnector connector, CollectionRequest request, IngestionRun run, boolean persistCursor) {
        String sourceId = connector.descriptor().id();
        if (!connector.descriptor().available()) {
            String reason = connector.descriptor().unavailableReasonOrEmpty().orElse("unavailable");
            log.info("Source {} skipped: {}", sourceId, reason);
            run.fail("SOURCE_UNAVAILABLE", reason, clock.instant());
            runs.save(run);
            return CollectionOutcome.unavailable(sourceId, reason);
        }

        Set<UUID> collected = new LinkedHashSet<>();
        int pagesCommitted = 0;
        Cursor startCursor = run.cursorBefore();

        try (DocumentStream stream = connector.collect(request, startCursor)) {
            Iterator<Document> iterator = stream.documents().iterator();
            List<Document> page = new ArrayList<>(properties.pageSize());
            int fetchedSoFar = 0;
            int rejectedSoFar = 0;

            while (iterator.hasNext()) {
                page.add(iterator.next());
                if (page.size() >= properties.pageSize()) {
                    var progress = commitPage(run, stream, page, collected, fetchedSoFar, rejectedSoFar, persistCursor);
                    fetchedSoFar = progress.fetched();
                    rejectedSoFar = progress.rejected();
                    pagesCommitted++;
                    page.clear();
                }
            }
            if (!page.isEmpty() || stream.fetched() > fetchedSoFar || stream.rejected() > rejectedSoFar) {
                commitPage(run, stream, page, collected, fetchedSoFar, rejectedSoFar, persistCursor);
                pagesCommitted++;
            }
            run.complete(clock.instant());
            runs.save(run);
            log.info(
                    "Source {} collected {} documents ({} new) for query '{}'",
                    sourceId,
                    collected.size(),
                    run.counters().created(),
                    request.normalizedQuery());
            return CollectionOutcome.succeeded(sourceId, run.id(), List.copyOf(collected), run.counters());
        } catch (RuntimeException e) {
            // BRULE-8: the source drops out of this run; everything already committed stays.
            String code = errorCodeOf(e);
            log.warn("Source {} failed after {} committed pages: {}", sourceId, pagesCommitted, e.toString());
            if (pagesCommitted > 0) {
                run.completePartially(code, e.toString(), clock.instant());
            } else {
                run.fail(code, e.toString(), clock.instant());
            }
            runs.save(run);
            return CollectionOutcome.failed(
                    sourceId, run.id(), List.copyOf(collected), run.counters(), code, e.toString());
        }
    }

    private Progress commitPage(
            IngestionRun run,
            DocumentStream stream,
            List<Document> page,
            Set<UUID> collected,
            int fetchedSoFar,
            int rejectedSoFar,
            boolean persistCursor) {
        var result = batchWriter.write(List.copyOf(page));
        collected.addAll(result.documentIds());

        int fetchedNow = stream.fetched();
        int rejectedNow = stream.rejected();
        var counters = new RunCounters(
                Math.max(fetchedNow - fetchedSoFar, 0),
                result.created(),
                result.duplicates(),
                Math.max(rejectedNow - rejectedSoFar, 0));

        Cursor cursor = stream.cursor();
        for (Document document : page) {
            cursor = cursor.withWatermark(document.publishedOn());
        }
        // Order matters: the page is already committed, so recording the cursor now can only ever
        // lag reality, never lead it.
        run.commitPage(cursor, counters);
        runs.save(run);
        if (persistCursor && !cursor.isStart()) {
            cursors.save(run.sourceId(), cursor, clock.instant());
        }
        return new Progress(fetchedNow, rejectedNow);
    }

    private static String errorCodeOf(RuntimeException e) {
        String name = e.getClass().getSimpleName();
        if (name.contains("CallNotPermitted")) {
            return "CIRCUIT_OPEN"; // FR-04.6: an open breaker marks the run partial, not broken
        }
        if (name.contains("Connector")) {
            return "UPSTREAM_ERROR";
        }
        return "COLLECTION_ERROR";
    }

    private record Progress(int fetched, int rejected) {}
}
