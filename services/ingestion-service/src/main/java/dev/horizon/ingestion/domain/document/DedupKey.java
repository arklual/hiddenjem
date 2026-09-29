package dev.horizon.ingestion.domain.document;

import java.util.List;

import dev.horizon.ingestion.domain.support.Hashing;
import dev.horizon.platform.common.util.Guards;

/**
 * The cross-source identity of a work (FR-04.3, BR-C4).
 *
 * <p>Precedence — DOI, then arXiv id, then a fingerprint of
 * {@code normalizedTitle | normalizedAuthors | year} — is domain logic, not a database concern: it
 * encodes what "the same document" means for this business. A registered identifier is authoritative
 * and cheap; the fingerprint is the fallback for preprints, patents, repositories and news that have
 * neither.
 *
 * <p>The value is always a 64-character SHA-256 digest, whatever the basis. Two reasons: the column
 * is {@code char(64)} and fixed-width keys index predictably, and hashing means a DOI-based key and
 * a title-based key can never collide by accident of formatting.
 *
 * <p>The {@link Basis} travels with the key for diagnostics — when two documents merge unexpectedly
 * an operator needs to know <em>why</em> they were considered identical.
 */
public record DedupKey(String value, Basis basis) {

    public enum Basis {
        DOI,
        ARXIV_ID,
        TITLE_AUTHORS_YEAR,
        /** Key read back from storage, where only the digest is persisted. */
        RESTORED
    }

    public DedupKey {
        value = Guards.requireText(value, "dedupKey.value").trim();
        Guards.requireArgument(value.length() == 64, "dedupKey must be a 64-character SHA-256 hex digest");
        Guards.requireNonNull(basis, "dedupKey.basis");
    }

    /**
     * Computes the key for a document.
     *
     * @param identifiers already-normalised identifiers (see {@link DocumentIdentifiers})
     * @param title raw title; normalisation happens here so callers cannot get it wrong
     * @param authorNames raw author names in source order — order does not affect the result
     * @param year year of publication
     */
    public static DedupKey compute(DocumentIdentifiers identifiers, String title, List<String> authorNames, int year) {
        Guards.requireNonNull(identifiers, "identifiers");
        if (identifiers.doi() != null) {
            return new DedupKey(Hashing.sha256Hex("doi:" + identifiers.doi()), Basis.DOI);
        }
        if (identifiers.arxivId() != null) {
            return new DedupKey(Hashing.sha256Hex("arxiv:" + identifiers.arxivId()), Basis.ARXIV_ID);
        }
        String normalizedTitle = TextNormalization.normalizeTitle(title);
        Guards.requireArgument(
                !normalizedTitle.isEmpty(), "a document without DOI/arXiv id must have a title to be deduplicated");
        String normalizedAuthors = TextNormalization.normalizeAuthors(authorNames);
        String canonical = normalizedTitle + "|" + normalizedAuthors + "|" + year;
        return new DedupKey(Hashing.sha256Hex(canonical), Basis.TITLE_AUTHORS_YEAR);
    }

    /** Rehydrates a key read back from storage, where only the digest is kept. */
    public static DedupKey ofStoredValue(String value) {
        return new DedupKey(value, Basis.RESTORED);
    }

    @Override
    public String toString() {
        return value;
    }
}
