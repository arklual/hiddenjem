package dev.horizon.ingestion.domain.document;

import java.time.Instant;

import dev.horizon.platform.common.util.Guards;

/**
 * Where a document came from and what exactly was received (FR-04.5, BR-C5).
 *
 * <p>Without provenance an analysis result cannot be audited: {@code payloadHash} pins the bytes the
 * canonical record was derived from, {@code rawRef} points at the archived response in object
 * storage, and {@code requestUrl} + {@code httpStatus} make the call itself reproducible.
 */
public record Provenance(
        String sourceId, Instant fetchedAt, String requestUrl, Integer httpStatus, String payloadHash, String rawRef) {

    public Provenance {
        sourceId = Guards.requireText(sourceId, "provenance.sourceId").trim();
        Guards.requireNonNull(fetchedAt, "provenance.fetchedAt");
        requestUrl = Guards.requireText(requestUrl, "provenance.requestUrl").trim();
        payloadHash = Guards.requireText(payloadHash, "provenance.payloadHash").trim();
        Guards.requireArgument(payloadHash.length() == 64, "payloadHash must be a 64-character SHA-256 hex digest");
        rawRef = rawRef == null || rawRef.isBlank() ? null : rawRef.trim();
    }
}
