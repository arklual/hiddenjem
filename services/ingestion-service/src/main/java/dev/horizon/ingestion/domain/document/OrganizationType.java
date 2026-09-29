package dev.horizon.ingestion.domain.document;

import java.util.Locale;
import java.util.Optional;

/**
 * Type of the organisation an author is affiliated with.
 *
 * <p>Mirrors the {@code organizationType} enum of {@code document-ingested.event.json} and the
 * {@code CHECK} constraint on {@code document_authors.organization_type}. Sources use their own
 * vocabularies (OpenAlex says {@code education}, Crossref and USPTO say it differently); mapping them here —
 * once, explicitly — is part of the anti-corruption layer.
 */
public enum OrganizationType {
    COMPANY,
    UNIVERSITY,
    RESEARCH_INSTITUTE,
    GOVERNMENT,
    NONPROFIT;

    /**
     * Maps a foreign institution-type token onto the canonical vocabulary.
     *
     * <p>Unknown tokens deliberately return {@link Optional#empty()} rather than a default: a wrong
     * organisation type silently skews the "diffusion across organisation types" indicator, whereas
     * a missing one is visibly missing.
     */
    public static Optional<OrganizationType> parse(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String token = value.trim().toLowerCase(Locale.ROOT);
        // Only unambiguous textual vocabularies are handled centrally. Numeric code systems (such as
        // patent assignee types) are mapped inside the
        // connector that owns them — the same digit means different things to different sources.
        return switch (token) {
            case "company", "corporate", "industry" -> Optional.of(COMPANY);
            case "education", "university", "academic" -> Optional.of(UNIVERSITY);
            case "facility", "research", "research_institute", "institute", "laboratory" -> Optional.of(
                    RESEARCH_INSTITUTE);
            case "government", "governmental" -> Optional.of(GOVERNMENT);
            case "nonprofit", "non-profit", "ngo", "archive", "healthcare" -> Optional.of(NONPROFIT);
            default -> {
                for (OrganizationType candidate : values()) {
                    if (candidate.name().equalsIgnoreCase(token)) {
                        yield Optional.of(candidate);
                    }
                }
                yield Optional.empty();
            }
        };
    }
}
