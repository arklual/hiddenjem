package dev.horizon.ingestion.connector.support;

import java.time.Instant;

import dev.horizon.ingestion.domain.document.Provenance;

/**
 * One successful upstream response, carrying everything provenance needs (FR-04.5).
 *
 * @param rawRef reference to the archived payload, or {@code null} when archiving is disabled
 */
public record RawHttpResponse(
        String sourceId,
        String requestUrl,
        int status,
        String body,
        String payloadHash,
        Instant fetchedAt,
        String rawRef) {

    /** Provenance for every document extracted from this response. */
    public Provenance provenance() {
        return new Provenance(sourceId, fetchedAt, requestUrl, status, payloadHash, rawRef);
    }
}
