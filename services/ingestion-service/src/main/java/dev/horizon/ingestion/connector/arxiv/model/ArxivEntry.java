package dev.horizon.ingestion.connector.arxiv.model;

import java.util.List;

import dev.horizon.ingestion.domain.document.Provenance;
import dev.horizon.ingestion.domain.port.RawDocument;

/**
 * An {@code <entry>} of an arXiv Atom feed, as parsed and no further.
 *
 * <p>Fields keep arXiv's own vocabulary ({@code summary} rather than "abstract",
 * {@code journalRef}, {@code primaryCategory}) so the translation happens in exactly one place —
 * {@code ArxivNormalizer} — and is reviewable there.
 */
public record ArxivEntry(
        String id,
        String title,
        String summary,
        String published,
        String updated,
        List<ArxivAuthor> authors,
        String doi,
        String journalRef,
        String primaryCategory,
        List<String> categories,
        String absUrl,
        String pdfUrl,
        String sourceId,
        String externalId,
        Provenance provenance)
        implements RawDocument {

    public ArxivEntry {
        authors = authors == null ? List.of() : List.copyOf(authors);
        categories = categories == null ? List.of() : List.copyOf(categories);
    }

    /** arXiv reports an affiliation per author, when the submitter bothered to fill it in. */
    public record ArxivAuthor(String name, String affiliation) {}
}
