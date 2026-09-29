package dev.horizon.ingestion.domain.document;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Impact signals attached to a document.
 *
 * <p>Citations are modelled explicitly because the impact indicator uses them directly; everything
 * else a source happens to expose (stars, forks, patent family size) lands in {@code extra} so that
 * a new signal never requires a schema migration.
 *
 * <p>Metrics are the only mutable aspect of a {@link Document}: they change over time while the
 * publication itself does not. Enrichment therefore produces a new instance with a bumped version
 * (optimistic locking), never an in-place edit.
 */
public record DocumentMetrics(Integer citationCount, Integer stars, Integer forks, Map<String, Number> extra) {

    public static final DocumentMetrics EMPTY = new DocumentMetrics(null, null, null, Map.of());

    public DocumentMetrics {
        citationCount = nonNegative(citationCount);
        stars = nonNegative(stars);
        forks = nonNegative(forks);
        // Sorted copy: the map is serialised into `extra_metrics` and into the DocumentIngested
        // payload, and a stable key order keeps payload hashes reproducible.
        extra = extra == null || extra.isEmpty() ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(extra));
    }

    public static DocumentMetrics ofCitations(Integer citationCount) {
        return new DocumentMetrics(citationCount, null, null, Map.of());
    }

    public static DocumentMetrics ofRepository(Integer stars, Integer forks, Map<String, Number> extra) {
        return new DocumentMetrics(null, stars, forks, extra);
    }

    /**
     * Flattened view written to {@code documents.extra_metrics} and to the {@code extraMetrics}
     * property of {@code document-ingested.event.json}.
     */
    public Map<String, Number> asExtraMetrics() {
        Map<String, Number> merged = new LinkedHashMap<>();
        if (stars != null) {
            merged.put("stars", stars);
        }
        if (forks != null) {
            merged.put("forks", forks);
        }
        merged.putAll(extra);
        return Collections.unmodifiableMap(merged);
    }

    public boolean isEmpty() {
        return citationCount == null && stars == null && forks == null && extra.isEmpty();
    }

    private static Integer nonNegative(Integer value) {
        return value == null || value < 0 ? null : value;
    }
}
