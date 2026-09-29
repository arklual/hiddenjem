package dev.horizon.ingestion.connector.rss.model;

import java.time.Instant;
import java.util.List;

import dev.horizon.ingestion.domain.document.Provenance;
import dev.horizon.ingestion.domain.port.RawDocument;

/**
 * A feed entry after Rome has flattened RSS 0.9x/1.0/2.0 and Atom 0.3/1.0 into one shape.
 *
 * <p>Rome is itself an anti-corruption layer against a decade of incompatible feed dialects; this
 * record is the second, narrower one that keeps Rome's types (and its {@code java.util.Date}s) out
 * of the domain.
 */
public record RssItem(
        String feedUrl,
        String feedTitle,
        String title,
        String link,
        String description,
        Instant publishedAt,
        List<String> authors,
        List<String> categories,
        String sourceId,
        String externalId,
        Provenance provenance)
        implements RawDocument {

    public RssItem {
        authors = authors == null ? List.of() : List.copyOf(authors);
        categories = categories == null ? List.of() : List.copyOf(categories);
    }
}
