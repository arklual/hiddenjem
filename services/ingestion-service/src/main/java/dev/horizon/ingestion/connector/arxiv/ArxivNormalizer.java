package dev.horizon.ingestion.connector.arxiv;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import dev.horizon.ingestion.connector.arxiv.model.ArxivEntry;
import dev.horizon.ingestion.domain.document.Author;
import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.document.DocumentTopic;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.document.Venue;
import dev.horizon.ingestion.domain.port.DocumentNormalizer;

/**
 * ACL for arXiv.
 *
 * <p>Decisions this layer makes, and why:
 *
 * <ul>
 *   <li><b>Class is {@code PREPRINT}</b> even when {@code journal_ref} shows the paper was later
 *       published: what we ingested <em>is</em> the preprint record, and the methodology weights
 *       preprints differently precisely because they are early signals.
 *   <li><b>{@code published}, not {@code updated}</b>, becomes {@code publishedOn}: novelty and
 *       first-mention year must reflect when the idea appeared, not when v4 fixed a typo.
 *   <li><b>Venue</b> is the journal reference when arXiv knows one, otherwise "arXiv" — an empty
 *       venue would make the same paper look venue-less to the diffusion indicator.
 *   <li><b>Language is assumed {@code en}</b>: arXiv's corpus is effectively English-only and the
 *       API exposes no language field. Recorded here as an explicit assumption rather than as a
 *       silent default elsewhere.
 * </ul>
 */
public class ArxivNormalizer implements DocumentNormalizer<ArxivEntry> {

    @Override
    public Document normalize(ArxivEntry entry) {
        LocalDate publishedOn = toLocalDate(entry.published());
        var builder = Document.builder()
                .externalRef(entry.sourceId(), entry.externalId())
                .sourceClass(SourceClass.PREPRINT)
                .title(entry.title())
                .abstractText(entry.summary())
                .language("en")
                .publishedOn(publishedOn)
                .doi(entry.doi())
                .arxivId(entry.externalId())
                .url(entry.absUrl())
                .venue(venueOf(entry))
                .provenance(entry.provenance());

        for (ArxivEntry.ArxivAuthor author : entry.authors()) {
            builder.author(new Author(author.name(), null, author.affiliation(), null, null));
        }
        String primary = entry.primaryCategory();
        if (primary != null) {
            builder.topic(DocumentTopic.of(primary, primary, 1.0));
        }
        for (String category : entry.categories()) {
            if (!category.equals(primary)) {
                builder.topic(DocumentTopic.of(category));
            }
        }
        return builder.build();
    }

    private static Venue venueOf(ArxivEntry entry) {
        String journalRef = entry.journalRef();
        return journalRef == null || journalRef.isBlank()
                ? new Venue("arXiv", "PREPRINT_SERVER", null)
                : new Venue(journalRef, "JOURNAL", null);
    }

    private static LocalDate toLocalDate(String timestamp) {
        if (timestamp == null || timestamp.isBlank()) {
            throw new IllegalArgumentException("arXiv entry without a publication date");
        }
        return LocalDate.ofInstant(Instant.parse(timestamp.trim()), ZoneOffset.UTC);
    }
}
