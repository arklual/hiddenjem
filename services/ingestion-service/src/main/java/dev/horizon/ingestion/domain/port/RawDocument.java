package dev.horizon.ingestion.domain.port;

import dev.horizon.ingestion.domain.document.Provenance;

/**
 * A record exactly as the external source returned it.
 *
 * <p>Implementations live inside their connector's package and are invisible to the rest of the
 * system — that invisibility <em>is</em> the anti-corruption layer, and an ArchUnit test proves no
 * class outside {@code connector.<name>} ever touches one.
 *
 * <p>Deliberately not sealed: adding a source must not require editing this file (NFR-M2, BR-C2).
 */
public interface RawDocument {

    /** Id of the connector that produced this record. */
    String sourceId();

    /** Identifier of the record in the source system. */
    String externalId();

    /** How and when the record was obtained — carried into the canonical document (FR-04.5). */
    Provenance provenance();
}
