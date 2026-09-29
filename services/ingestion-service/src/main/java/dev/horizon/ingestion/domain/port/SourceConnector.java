package dev.horizon.ingestion.domain.port;

import java.util.stream.Stream;

import dev.horizon.ingestion.domain.run.Cursor;

/**
 * The extension point of the whole service (NFR-M2, BR-C2): one interface, one new source.
 *
 * <p>Implementations are driven adapters — they know URLs, XML namespaces and rate-limit headers.
 * Nothing above this port does.
 *
 * <p>{@link #fetch} returns a <em>lazy</em> stream: pages are requested from the source only as the
 * consumer pulls documents, so a collection that stops at {@code maxDocuments} never pays for the
 * pages it would not have used, and memory stays flat regardless of corpus size.
 */
public interface SourceConnector {

    SourceDescriptor descriptor();

    /**
     * Whether this connector can serve the request at all — wrong source class, missing credentials
     * or a query it cannot express. Returning {@code false} is not a failure; the connector is
     * simply not part of this collection.
     */
    boolean supports(CollectionRequest request);

    /** Lazy, paged retrieval starting at {@code cursor}. */
    Stream<RawDocument> fetch(CollectionRequest request, Cursor cursor);
}
