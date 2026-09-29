package dev.horizon.ingestion.domain.document;

import dev.horizon.platform.common.util.Guards;

/**
 * Identity of a document in the system it came from.
 *
 * <p>{@code (sourceId, externalId)} is unique per publication date — the first line of defence
 * against re-ingesting the same record (invariant of §5 of the domain model, enforced by
 * {@code ux_documents_source_external}). {@link DedupKey} is the second line: it catches the same
 * work arriving from a <em>different</em> source.
 */
public record ExternalRef(String sourceId, String externalId) {

    public static final int MAX_SOURCE_ID_LENGTH = 48;
    public static final int MAX_EXTERNAL_ID_LENGTH = 200;

    public ExternalRef {
        sourceId = Guards.requireText(sourceId, "sourceId").trim();
        externalId = Guards.requireText(externalId, "externalId").trim();
        Guards.requireArgument(
                sourceId.length() <= MAX_SOURCE_ID_LENGTH,
                "sourceId must not exceed " + MAX_SOURCE_ID_LENGTH + " chars");
        Guards.requireArgument(
                externalId.length() <= MAX_EXTERNAL_ID_LENGTH,
                "externalId must not exceed " + MAX_EXTERNAL_ID_LENGTH + " chars");
    }

    @Override
    public String toString() {
        return sourceId + ":" + externalId;
    }
}
