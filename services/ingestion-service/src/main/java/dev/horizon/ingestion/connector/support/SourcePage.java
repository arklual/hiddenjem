package dev.horizon.ingestion.connector.support;

import java.util.List;

import dev.horizon.ingestion.domain.port.RawDocument;
import dev.horizon.ingestion.domain.run.Cursor;

/**
 * One page as a connector fetched it.
 *
 * @param items raw records in source order
 * @param next cursor to use for the following page
 * @param last whether the source says there is nothing after this page — trusted when present,
 *     because guessing from "fewer items than requested" breaks on sources that return short pages
 */
public record SourcePage<R extends RawDocument>(List<R> items, Cursor next, boolean last) {

    public SourcePage {
        items = List.copyOf(items);
        next = next == null ? Cursor.start() : next;
    }

    public static <R extends RawDocument> SourcePage<R> last(List<R> items, Cursor next) {
        return new SourcePage<>(items, next, true);
    }

    public static <R extends RawDocument> SourcePage<R> more(List<R> items, Cursor next) {
        return new SourcePage<>(items, next, false);
    }

    public static <R extends RawDocument> SourcePage<R> empty() {
        return new SourcePage<>(List.of(), Cursor.start(), true);
    }
}
