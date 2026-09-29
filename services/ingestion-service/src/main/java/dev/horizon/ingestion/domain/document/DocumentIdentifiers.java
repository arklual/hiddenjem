package dev.horizon.ingestion.domain.document;

import java.util.Locale;
import java.util.Optional;

import dev.horizon.platform.common.util.Guards;

/**
 * Cross-source identifiers of a document plus its canonical URL.
 *
 * <p>The identifiers are normalised at construction — a DOI written as
 * {@code https://doi.org/10.1000/ABC}, {@code doi:10.1000/abc} and {@code 10.1000/abc} must produce
 * one and the same {@link DedupKey}, otherwise deduplication silently degrades to "per source".
 */
public record DocumentIdentifiers(String doi, String arxivId, String patentNumber, String url) {

    public DocumentIdentifiers {
        doi = normalizeDoi(doi);
        arxivId = normalizeArxivId(arxivId);
        patentNumber = blankToNull(patentNumber);
        url = Guards.requireText(url, "url").trim();
    }

    public static DocumentIdentifiers ofUrl(String url) {
        return new DocumentIdentifiers(null, null, null, url);
    }

    public Optional<String> doiOrEmpty() {
        return Optional.ofNullable(doi);
    }

    public Optional<String> arxivIdOrEmpty() {
        return Optional.ofNullable(arxivId);
    }

    /**
     * Strips the resolver prefix, the {@code doi:} scheme and case: DOIs are case-insensitive by
     * specification, and registrars are inconsistent about which case they publish.
     */
    public static String normalizeDoi(String raw) {
        String value = blankToNull(raw);
        if (value == null) {
            return null;
        }
        String doi = value.trim();
        String lower = doi.toLowerCase(Locale.ROOT);
        for (String prefix : new String[] {"https://doi.org/", "http://doi.org/", "https://dx.doi.org/", "doi:"}) {
            if (lower.startsWith(prefix)) {
                doi = doi.substring(prefix.length());
                break;
            }
        }
        doi = doi.trim().toLowerCase(Locale.ROOT);
        while (doi.endsWith(".") || doi.endsWith(",") || doi.endsWith(";")) {
            doi = doi.substring(0, doi.length() - 1);
        }
        return doi.isBlank() ? null : doi;
    }

    /**
     * Normalises an arXiv identifier to its version-less canonical form
     * ({@code arXiv:2401.01234v3} → {@code 2401.01234}); versions are revisions of one work and must
     * not create two documents.
     */
    public static String normalizeArxivId(String raw) {
        String value = blankToNull(raw);
        if (value == null) {
            return null;
        }
        String id = value.trim();
        int lastSlash = id.lastIndexOf("abs/");
        if (lastSlash >= 0) {
            id = id.substring(lastSlash + 4);
        }
        String lower = id.toLowerCase(Locale.ROOT);
        if (lower.startsWith("arxiv:")) {
            id = id.substring("arxiv:".length());
        }
        id = id.trim().toLowerCase(Locale.ROOT);
        int versionMarker = id.lastIndexOf('v');
        if (versionMarker > 0 && isDigits(id.substring(versionMarker + 1))) {
            id = id.substring(0, versionMarker);
        }
        return id.isBlank() ? null : id;
    }

    private static boolean isDigits(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
