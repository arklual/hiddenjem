package dev.horizon.ingestion.domain.snapshot;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

import dev.horizon.ingestion.domain.support.Hashing;
import dev.horizon.platform.common.util.Guards;

/**
 * The frozen set of documents an analysis is computed over (ADR-0015, BR-B6).
 *
 * <p>Reproducibility is the whole point: {@code contentHash} is the SHA-256 of the <em>sorted,
 * de-duplicated</em> document ids, so two collections that found the same documents produce the same
 * hash no matter in which order the connectors returned them, how many pages each took, or how many
 * duplicates were filtered. Re-running the methodology against the same hash must yield the same
 * report; a differing hash is the honest signal that the input changed.
 *
 * <p>Sorting is what makes it deterministic, and de-duplication is what makes it correct: the same
 * document can legitimately be produced by two connectors in one collection.
 */
public record CorpusSnapshot(
        UUID id,
        String normalizedQuery,
        LocalDate windowFrom,
        LocalDate windowTo,
        List<UUID> documentIds,
        List<String> sourcesUsed,
        List<String> unavailableSources,
        boolean partial,
        String contentHash) {

    public CorpusSnapshot {
        Guards.requireNonNull(id, "snapshot.id");
        normalizedQuery = Guards.requireText(normalizedQuery, "snapshot.normalizedQuery");
        Guards.requireNonNull(windowFrom, "snapshot.windowFrom");
        Guards.requireNonNull(windowTo, "snapshot.windowTo");
        Guards.requireArgument(!windowFrom.isAfter(windowTo), "windowFrom must not be after windowTo");
        documentIds = List.copyOf(documentIds);
        sourcesUsed = List.copyOf(sourcesUsed);
        unavailableSources = List.copyOf(unavailableSources);
        Guards.requireText(contentHash, "snapshot.contentHash");
        Guards.requireArgument(contentHash.length() == 64, "contentHash must be a 64-character SHA-256 hex digest");
    }

    /**
     * Assembles a snapshot from what a collection produced.
     *
     * @param documentIds ids of every document matched for the query and window, in any order and
     *     possibly with repetitions
     * @param sourcesUsed connectors that contributed
     * @param unavailableSources connectors that could not be reached — their presence makes the
     *     snapshot {@code partial} (BR-C7, BRULE-8)
     */
    public static CorpusSnapshot assemble(
            UUID id,
            String normalizedQuery,
            LocalDate windowFrom,
            LocalDate windowTo,
            Collection<UUID> documentIds,
            Collection<String> sourcesUsed,
            Collection<String> unavailableSources) {
        List<UUID> ordered = sortedDistinct(documentIds);
        List<String> used = sortedDistinctStrings(sourcesUsed);
        List<String> unavailable = sortedDistinctStrings(unavailableSources);
        return new CorpusSnapshot(
                id,
                normalizedQuery,
                windowFrom,
                windowTo,
                ordered,
                used,
                unavailable,
                !unavailable.isEmpty(),
                contentHashOf(ordered));
    }

    /**
     * SHA-256 over the newline-joined sorted ids.
     *
     * <p>A separator is used rather than plain concatenation so that no rearrangement of id
     * boundaries can produce the same byte stream from a different set.
     */
    public static String contentHashOf(Collection<UUID> documentIds) {
        List<UUID> ordered = sortedDistinct(documentIds);
        var joined = new StringBuilder(ordered.size() * 37);
        for (UUID documentId : ordered) {
            joined.append(documentId).append('\n');
        }
        return Hashing.sha256Hex(joined.toString());
    }

    public int documentCount() {
        return documentIds.size();
    }

    public boolean isEmpty() {
        return documentIds.isEmpty();
    }

    private static List<UUID> sortedDistinct(Collection<UUID> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(new TreeSet<>(values));
    }

    private static List<String> sortedDistinctStrings(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(new TreeSet<>(values));
    }
}
