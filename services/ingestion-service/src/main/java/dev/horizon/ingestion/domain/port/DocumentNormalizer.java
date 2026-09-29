package dev.horizon.ingestion.domain.port;

import dev.horizon.ingestion.domain.document.Document;

/**
 * The anti-corruption layer itself: the single place where a foreign model becomes a
 * {@link Document}.
 *
 * <p>One normalizer per connector, and it is the <em>only</em> class allowed to see that
 * connector's raw types. Everything the source got wrong — inverted abstracts, JATS markup in a
 * field declared as text, dates as three integers, "et al." in an author list — is corrected here
 * and never leaks further.
 *
 * <p>A normalizer that cannot produce a valid canonical document throws; the caller counts the
 * record as {@code rejected} and moves on, because one malformed record must not abort a run.
 *
 * @param <R> the connector's raw document type
 */
@FunctionalInterface
public interface DocumentNormalizer<R extends RawDocument> {

    Document normalize(R raw);
}
