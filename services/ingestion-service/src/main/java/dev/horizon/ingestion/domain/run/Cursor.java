package dev.horizon.ingestion.domain.run;

import java.time.LocalDate;
import java.util.Optional;

/**
 * Position of an incremental crawl inside a source (FR-04.7).
 *
 * <p>Two complementary coordinates because sources disagree about what a cursor is: OpenAlex and
 * Crossref hand out an opaque {@code value} token, arXiv and GitHub page by offset, and RSS has no
 * cursor at all — for those {@code lastPublishedOn} is the watermark that keeps the next run from
 * re-reading history.
 *
 * <p>A cursor is only ever persisted after the page it points past has been committed
 * ({@link IngestionRun#commitPage}); at-least-once re-fetching is harmless because ingestion is
 * idempotent, whereas skipping a page loses documents forever.
 */
public record Cursor(String value, LocalDate lastPublishedOn) {

    private static final Cursor START = new Cursor(null, null);

    public Cursor {
        value = value == null || value.isBlank() ? null : value.trim();
    }

    /** The beginning of time: no token, no watermark. */
    public static Cursor start() {
        return START;
    }

    public static Cursor ofValue(String value) {
        return new Cursor(value, null);
    }

    public static Cursor ofDate(LocalDate lastPublishedOn) {
        return new Cursor(null, lastPublishedOn);
    }

    public boolean isStart() {
        return value == null && lastPublishedOn == null;
    }

    public Optional<String> valueOrEmpty() {
        return Optional.ofNullable(value);
    }

    public Optional<LocalDate> lastPublishedOnOrEmpty() {
        return Optional.ofNullable(lastPublishedOn);
    }

    /** Moves the watermark forward only — a source that returns older records must not rewind it. */
    public Cursor withWatermark(LocalDate candidate) {
        if (candidate == null) {
            return this;
        }
        if (lastPublishedOn == null || candidate.isAfter(lastPublishedOn)) {
            return new Cursor(value, candidate);
        }
        return this;
    }

    public Cursor withValue(String newValue) {
        return new Cursor(newValue, lastPublishedOn);
    }
}
