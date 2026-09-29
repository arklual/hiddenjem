package dev.horizon.ingestion.domain.port;

import dev.horizon.ingestion.domain.run.Cursor;

/**
 * A {@link SourceConnector} bundled with the normalizer that closes its anti-corruption layer.
 *
 * <p>{@link SourceConnector} is the contract a new source implements; this sub-interface is what the
 * application consumes. Keeping them apart is what lets the raw types stay package-private to the
 * connector: the application never sees a {@link RawDocument}, only {@code Document}s that have
 * already crossed the ACL.
 *
 * <p>Implementations get all of this for free by extending the shared connector base class, so the
 * cost of adding a source remains "implement one page fetch and one normalizer".
 */
public interface NormalizingSourceConnector extends SourceConnector {

    /**
     * Collects documents starting at {@code cursor}, already normalised.
     *
     * <p>The returned stream is lazy and must be closed by the caller.
     */
    DocumentStream collect(CollectionRequest request, Cursor cursor);

    /** Convenience for the common case of "collect from the beginning". */
    default DocumentStream collect(CollectionRequest request) {
        return collect(request, Cursor.start());
    }
}
