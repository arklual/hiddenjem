package dev.horizon.ingestion.domain.port;

import java.util.stream.Stream;

import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.run.Cursor;

/**
 * A lazily paged flow of canonical documents, plus the bookkeeping the caller needs to persist it
 * safely.
 *
 * <p>Why this exists next to {@link SourceConnector#fetch}: the application must know two things a
 * bare {@code Stream} cannot express — how far the source has actually been read (to advance the
 * cursor) and how many records were pulled or rejected (to fill the run counters).
 *
 * <p>{@link #cursor()} deliberately reports the position after the last <em>fully consumed</em>
 * page. Documents from a half-read page are simply re-fetched on the next run, which is free
 * because ingestion is idempotent, whereas reporting a cursor past unread records would lose them.
 */
public interface DocumentStream extends AutoCloseable {

    /** The documents; consuming this stream is what drives paging. */
    Stream<Document> documents();

    /** Position after the last fully consumed page. */
    Cursor cursor();

    /** Raw records pulled from the source so far. */
    int fetched();

    /** Raw records that could not be normalised and were skipped. */
    int rejected();

    @Override
    void close();

    /** An empty stream, returned by connectors that decline a request. */
    static DocumentStream empty(Cursor cursor) {
        return new DocumentStream() {
            @Override
            public Stream<Document> documents() {
                return Stream.empty();
            }

            @Override
            public Cursor cursor() {
                return cursor == null ? Cursor.start() : cursor;
            }

            @Override
            public int fetched() {
                return 0;
            }

            @Override
            public int rejected() {
                return 0;
            }

            @Override
            public void close() {
                // nothing to release
            }
        };
    }
}
