package dev.horizon.ingestion.connector.support;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.port.CollectionRequest;
import dev.horizon.ingestion.domain.port.DocumentNormalizer;
import dev.horizon.ingestion.domain.port.DocumentStream;
import dev.horizon.ingestion.domain.port.NormalizingSourceConnector;
import dev.horizon.ingestion.domain.port.RawDocument;
import dev.horizon.ingestion.domain.run.Cursor;

/**
 * Everything a connector needs except the two things that are actually source-specific: how to fetch
 * one page, and how to normalise one record.
 *
 * <p>That is the point of NFR-M2 — adding a source is {@link #fetchPage} plus a
 * {@link DocumentNormalizer}, roughly a hundred lines, with paging, laziness, cursor bookkeeping,
 * counters and per-record error isolation inherited from here.
 *
 * <p>Two behaviours are worth reading closely:
 *
 * <p><b>Laziness.</b> The stream is backed by a spliterator that fetches the next page only when the
 * consumer has drained the current one. A collection that stops at its document budget therefore
 * never pays for a page it would not have used — which is real money in rate-limited API calls.
 *
 * <p><b>Per-record isolation.</b> A record that fails normalisation is counted as {@code rejected}
 * and skipped. One malformed author list among five hundred documents must not abort a run; the
 * counter makes the loss visible in the run history instead of hiding it.
 *
 * @param <R> this connector's raw document type — never visible above the ACL
 */
public abstract class AbstractSourceConnector<R extends RawDocument> implements NormalizingSourceConnector {

    private static final Logger log = LoggerFactory.getLogger(AbstractSourceConnector.class);

    /** The ACL for this source. */
    protected abstract DocumentNormalizer<R> normalizer();

    /** Fetches a single page beginning at {@code cursor}. Implementations do the HTTP/IO work. */
    protected abstract SourcePage<R> fetchPage(CollectionRequest request, Cursor cursor);

    @Override
    public Stream<RawDocument> fetch(CollectionRequest request, Cursor cursor) {
        Pager pager = new Pager(request, cursor);
        return pager.stream().map(raw -> (RawDocument) raw);
    }

    @Override
    public DocumentStream collect(CollectionRequest request, Cursor cursor) {
        Pager pager = new Pager(request, cursor);
        DocumentNormalizer<R> normalizer = normalizer();
        int[] rejected = new int[1];
        Stream<Document> documents = pager.stream()
                .map(raw -> {
                    try {
                        return normalizer.normalize(raw);
                    } catch (RuntimeException e) {
                        rejected[0]++;
                        log.debug("Rejected record {} from {}: {}", raw.externalId(), raw.sourceId(), e.toString());
                        return null;
                    }
                })
                .filter(Objects::nonNull);
        return new PagedDocumentStream(documents, pager, rejected);
    }

    /**
     * Lazily walks pages, remembering how far the source has been read.
     *
     * <p>{@code committedCursor} lags {@code pendingCursor} by exactly one page: it is promoted only
     * when the previous page has been fully consumed, so a caller that persists it can never claim
     * to have processed records it has not seen.
     */
    private final class Pager {

        private final CollectionRequest request;
        private Cursor pendingCursor;
        private Cursor committedCursor;
        private Iterator<R> current = Collections.emptyIterator();
        private boolean exhausted;
        private int fetched;

        private Pager(CollectionRequest request, Cursor cursor) {
            this.request = request;
            this.pendingCursor = cursor == null ? Cursor.start() : cursor;
            this.committedCursor = this.pendingCursor;
        }

        private Stream<R> stream() {
            Spliterator<R> spliterator =
                    new Spliterators.AbstractSpliterator<>(Long.MAX_VALUE, Spliterator.ORDERED | Spliterator.NONNULL) {
                        @Override
                        public boolean tryAdvance(Consumer<? super R> action) {
                            // Лимит соблюдается посреди страницы, а не на её границе. Раньше
                            // страница дочитывалась целиком: Semantic Scholar отдаёт тысячу
                            // записей за раз и на просьбу о двадцати четырёх приносил 976. Корпус
                            // выходил втрое больше бюджета, анализ усекал его в порядке поступления
                            // и выбрасывал ровно то, что собрано последним. Курсор недочитанной
                            // страницы не продвигается: следующий обход перечитает её, а не
                            // пропустит.
                            if (fetched >= request.maxDocuments()) {
                                if (!current.hasNext()) {
                                    committedCursor = pendingCursor;
                                }
                                return false;
                            }
                            while (!current.hasNext()) {
                                if (exhausted || fetched >= request.maxDocuments()) {
                                    committedCursor = pendingCursor;
                                    return false;
                                }
                                // The page we were reading is done: its cursor may now be trusted.
                                committedCursor = pendingCursor;
                                SourcePage<R> page = fetchPage(request, pendingCursor);
                                List<R> items = page.items();
                                pendingCursor = page.next();
                                exhausted = page.last();
                                if (items.isEmpty()) {
                                    if (exhausted) {
                                        committedCursor = pendingCursor;
                                        return false;
                                    }
                                    continue;
                                }
                                current = items.iterator();
                            }
                            R item = current.next();
                            fetched++;
                            action.accept(item);
                            return true;
                        }
                    };
            return StreamSupport.stream(spliterator, false);
        }

        private Cursor cursor() {
            return committedCursor;
        }

        private int fetched() {
            return fetched;
        }
    }

    /** Adapts a lazily paged stream to the {@link DocumentStream} port. */
    private final class PagedDocumentStream implements DocumentStream {

        private final Stream<Document> documents;
        private final Pager pager;
        private final int[] rejected;

        private PagedDocumentStream(Stream<Document> documents, Pager pager, int[] rejected) {
            this.documents = documents;
            this.pager = pager;
            this.rejected = rejected;
        }

        @Override
        public Stream<Document> documents() {
            return documents;
        }

        @Override
        public Cursor cursor() {
            return pager.cursor();
        }

        @Override
        public int fetched() {
            return pager.fetched();
        }

        @Override
        public int rejected() {
            return rejected[0];
        }

        @Override
        public void close() {
            documents.close();
        }
    }
}
