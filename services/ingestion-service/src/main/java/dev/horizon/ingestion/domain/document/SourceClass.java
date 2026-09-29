package dev.horizon.ingestion.domain.document;

import java.util.Optional;

/**
 * Class of a source document — the vocabulary shared with the analysis engine.
 *
 * <p>Values are part of the published language ({@code document-ingested.event.json},
 * {@code SourceClass} in the OpenAPI contract) and of the {@code CHECK} constraint on
 * {@code documents.source_class}; renaming one is a breaking change.
 */
public enum SourceClass {
    PREPRINT,
    JOURNAL_ARTICLE,
    PATENT,
    CODE_REPOSITORY,
    NEWS,
    ANALYST_REPORT,
    STANDARD;

    /** Lenient parsing for external payloads; unknown values yield {@link Optional#empty()}. */
    public static Optional<SourceClass> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        for (SourceClass candidate : values()) {
            if (candidate.name().equalsIgnoreCase(value.trim())) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
