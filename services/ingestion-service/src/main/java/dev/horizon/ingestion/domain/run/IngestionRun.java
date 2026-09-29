package dev.horizon.ingestion.domain.run;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import dev.horizon.platform.common.error.HorizonException;
import dev.horizon.platform.common.util.Guards;

/**
 * One execution of one connector — aggregate root (domain model §5).
 *
 * <p>Mutable on purpose: a run is a <em>process</em>, and its counters and cursor advance while it
 * executes. All mutation goes through intention-revealing methods that enforce two invariants:
 *
 * <ol>
 *   <li>the status machine {@code RUNNING → COMPLETED | PARTIAL | FAILED}, terminal states final;
 *   <li><b>the cursor only advances on a committed page</b> — {@link #commitPage} is the single
 *       writer of {@code cursorAfter}, and it is called by the application only after the page's
 *       documents and their outbox rows are committed. Advancing earlier would silently drop
 *       documents on a crash; advancing never would re-read the whole source every run.
 * </ol>
 */
public final class IngestionRun {

    private final UUID id;
    private final String sourceId;
    private final RunMode mode;
    private final UUID researchRequestId;
    private final String query;
    private final LocalDate windowFrom;
    private final LocalDate windowTo;
    private final Cursor cursorBefore;
    private final Instant startedAt;
    private final String traceId;

    private RunStatus status;
    private Cursor cursorAfter;
    private RunCounters counters;
    private String errorCode;
    private String errorMessage;
    private Instant finishedAt;

    private IngestionRun(
            UUID id,
            String sourceId,
            RunMode mode,
            UUID researchRequestId,
            String query,
            LocalDate windowFrom,
            LocalDate windowTo,
            Cursor cursorBefore,
            Instant startedAt,
            String traceId,
            RunStatus status,
            Cursor cursorAfter,
            RunCounters counters,
            String errorCode,
            String errorMessage,
            Instant finishedAt) {
        this.id = Guards.requireNonNull(id, "run.id");
        this.sourceId = Guards.requireText(sourceId, "run.sourceId");
        this.mode = Guards.requireNonNull(mode, "run.mode");
        this.researchRequestId = researchRequestId;
        this.query = query;
        this.windowFrom = windowFrom;
        this.windowTo = windowTo;
        this.cursorBefore = cursorBefore == null ? Cursor.start() : cursorBefore;
        this.startedAt = Guards.requireNonNull(startedAt, "run.startedAt");
        this.traceId = traceId;
        this.status = Guards.requireNonNull(status, "run.status");
        this.cursorAfter = cursorAfter;
        this.counters = counters == null ? RunCounters.ZERO : counters;
        this.errorCode = errorCode;
        this.errorMessage = errorMessage;
        this.finishedAt = finishedAt;
        if (windowFrom != null && windowTo != null) {
            Guards.requireArgument(!windowFrom.isAfter(windowTo), "windowFrom must not be after windowTo");
        }
    }

    /** Starts a run in {@link RunStatus#RUNNING}. */
    public static IngestionRun start(
            UUID id,
            String sourceId,
            RunMode mode,
            UUID researchRequestId,
            String query,
            LocalDate windowFrom,
            LocalDate windowTo,
            Cursor cursorBefore,
            Instant startedAt,
            String traceId) {
        return new IngestionRun(
                id,
                sourceId,
                mode,
                researchRequestId,
                query,
                windowFrom,
                windowTo,
                cursorBefore,
                startedAt,
                traceId,
                RunStatus.RUNNING,
                null,
                RunCounters.ZERO,
                null,
                null,
                null);
    }

    /** Rehydrates a run from storage. */
    public static IngestionRun rehydrate(
            UUID id,
            String sourceId,
            RunMode mode,
            UUID researchRequestId,
            String query,
            LocalDate windowFrom,
            LocalDate windowTo,
            Cursor cursorBefore,
            Cursor cursorAfter,
            RunStatus status,
            RunCounters counters,
            String errorCode,
            String errorMessage,
            Instant startedAt,
            Instant finishedAt,
            String traceId) {
        return new IngestionRun(
                id,
                sourceId,
                mode,
                researchRequestId,
                query,
                windowFrom,
                windowTo,
                cursorBefore,
                startedAt,
                traceId,
                status,
                cursorAfter,
                counters,
                errorCode,
                errorMessage,
                finishedAt);
    }

    /**
     * Records a page whose documents are already committed and advances the cursor.
     *
     * @param cursor position after the committed page; {@code null} leaves the cursor untouched,
     *     which is what a source without a cursor concept (RSS) reports
     */
    public void commitPage(Cursor cursor, RunCounters pageCounters) {
        requireRunning("commit a page");
        this.counters = this.counters.plus(Guards.requireNonNull(pageCounters, "pageCounters"));
        if (cursor != null && !cursor.isStart()) {
            this.cursorAfter = cursor;
        }
    }

    /** Counts records the source returned but that never reached persistence. */
    public void recordRejected(int rejected) {
        requireRunning("record rejected documents");
        this.counters = this.counters.plus(0, 0, 0, Math.max(rejected, 0));
    }

    public void complete(Instant at) {
        transitionTo(RunStatus.COMPLETED, at);
    }

    /** Finished, but some pages or sub-feeds were lost — the result is usable and incomplete. */
    public void completePartially(String code, String message, Instant at) {
        this.errorCode = truncate(code, 48);
        this.errorMessage = message;
        transitionTo(RunStatus.PARTIAL, at);
    }

    public void fail(String code, String message, Instant at) {
        this.errorCode = truncate(code, 48);
        this.errorMessage = message;
        transitionTo(RunStatus.FAILED, at);
    }

    private void transitionTo(RunStatus next, Instant at) {
        if (!status.canTransitionTo(next)) {
            throw HorizonException.illegalTransition(
                    "Ingestion run %s cannot move from %s to %s".formatted(id, status, next));
        }
        this.status = next;
        this.finishedAt = Guards.requireNonNull(at, "finishedAt");
    }

    private void requireRunning(String action) {
        if (status != RunStatus.RUNNING) {
            throw HorizonException.illegalTransition("Cannot %s: run %s is already %s".formatted(action, id, status));
        }
    }

    public UUID id() {
        return id;
    }

    public String sourceId() {
        return sourceId;
    }

    public RunMode mode() {
        return mode;
    }

    public UUID researchRequestId() {
        return researchRequestId;
    }

    public String query() {
        return query;
    }

    public LocalDate windowFrom() {
        return windowFrom;
    }

    public LocalDate windowTo() {
        return windowTo;
    }

    public Cursor cursorBefore() {
        return cursorBefore;
    }

    /** Position after the last <em>committed</em> page; empty when no page was committed. */
    public Optional<Cursor> cursorAfter() {
        return Optional.ofNullable(cursorAfter);
    }

    public RunStatus status() {
        return status;
    }

    public RunCounters counters() {
        return counters;
    }

    public String errorCode() {
        return errorCode;
    }

    public String errorMessage() {
        return errorMessage;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant finishedAt() {
        return finishedAt;
    }

    public String traceId() {
        return traceId;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof IngestionRun other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "IngestionRun[%s %s %s %s]".formatted(id, sourceId, mode, status);
    }
}
