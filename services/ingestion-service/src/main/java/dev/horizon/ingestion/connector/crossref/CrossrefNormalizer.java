package dev.horizon.ingestion.connector.crossref;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import dev.horizon.ingestion.connector.crossref.model.CrossrefResponse;
import dev.horizon.ingestion.domain.document.Author;
import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.document.DocumentMetrics;
import dev.horizon.ingestion.domain.document.DocumentTopic;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.document.Venue;
import dev.horizon.ingestion.domain.port.DocumentNormalizer;

/**
 * ACL for Crossref.
 *
 * <p>Three translations carry their weight here:
 *
 * <ul>
 *   <li><b>JATS is stripped from the abstract.</b> Crossref returns publisher-supplied XML
 *       ({@code <jats:p>}, {@code <jats:italic>}); passing that to term extraction would make
 *       "jats" one of the most frequent tokens in the corpus.
 *   <li><b>Date parts become a real date.</b> {@code [[2024, 5]]} means May 2024, which is anchored
 *       to the first of the month; a missing date falls back through online → print → issued.
 *   <li><b>Type maps to source class</b>, so a {@code posted-content} record is recognised as a
 *       preprint rather than counted as a peer-reviewed article.
 * </ul>
 */
public class CrossrefNormalizer implements DocumentNormalizer<CrossrefResponse.Raw> {

    private static final Pattern XML_TAG = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    @Override
    public Document normalize(CrossrefResponse.Raw raw) {
        CrossrefResponse.Item item = raw.item();
        var builder = Document.builder()
                .externalRef(raw.sourceId(), raw.externalId())
                .sourceClass(sourceClassOf(item.type()))
                .title(titleOf(item))
                .abstractText(stripMarkup(item.abstractText()))
                .language(item.language())
                .publishedOn(publishedOn(item))
                .doi(item.doi())
                .url(urlOf(item))
                .venue(venueOf(item))
                .metrics(DocumentMetrics.ofCitations(item.referencedByCount()))
                .provenance(raw.provenance());

        for (CrossrefResponse.Author author : item.author()) {
            builder.author(authorOf(author));
        }
        for (String subject : item.subject()) {
            builder.topic(DocumentTopic.of(subject, subject, null));
        }
        return builder.build();
    }

    /** Removes JATS/HTML markup and collapses the whitespace it leaves behind. */
    static String stripMarkup(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String withoutTags = XML_TAG.matcher(value).replaceAll(" ");
        String unescaped = withoutTags
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'");
        String collapsed = WHITESPACE.matcher(unescaped).replaceAll(" ").trim();
        return collapsed.isEmpty() ? null : collapsed;
    }

    private static Author authorOf(CrossrefResponse.Author author) {
        String fullName = fullNameOf(author);
        String organization = author.affiliation().isEmpty()
                ? null
                : author.affiliation().get(0).name();
        return new Author(fullName, author.orcid(), organization, null, null);
    }

    private static String fullNameOf(CrossrefResponse.Author author) {
        if (author.name() != null && !author.name().isBlank()) {
            return author.name(); // organisational author ("The LHCb Collaboration")
        }
        String given = author.given() == null ? "" : author.given().trim();
        String family = author.family() == null ? "" : author.family().trim();
        String joined = (given + " " + family).trim();
        if (joined.isEmpty()) {
            throw new IllegalArgumentException("Crossref author without a name");
        }
        return joined;
    }

    private static String titleOf(CrossrefResponse.Item item) {
        for (String title : item.title()) {
            if (title != null && !title.isBlank()) {
                return stripMarkup(title);
            }
        }
        throw new IllegalArgumentException("Crossref item without a title: " + item.doi());
    }

    private static Venue venueOf(CrossrefResponse.Item item) {
        String name = item.containerTitle().isEmpty()
                ? item.publisher()
                : item.containerTitle().get(0);
        String issn = item.issn().isEmpty() ? null : item.issn().get(0);
        return Venue.orNull(name, item.type(), issn);
    }

    private static String urlOf(CrossrefResponse.Item item) {
        if (item.url() != null && !item.url().isBlank()) {
            return item.url();
        }
        if (item.doi() != null && !item.doi().isBlank()) {
            return "https://doi.org/" + item.doi();
        }
        throw new IllegalArgumentException("Crossref item without a URL or DOI");
    }

    private static SourceClass sourceClassOf(String type) {
        String value = type == null ? "" : type.toLowerCase(Locale.ROOT);
        return switch (value) {
            case "posted-content" -> SourceClass.PREPRINT;
            case "standard" -> SourceClass.STANDARD;
            case "report", "report-component" -> SourceClass.ANALYST_REPORT;
            default -> SourceClass.JOURNAL_ARTICLE;
        };
    }

    /**
     * Prefers the earliest concrete publication date: online first (that is when the work became
     * visible), then print, then the generic {@code issued}.
     */
    private static LocalDate publishedOn(CrossrefResponse.Item item) {
        LocalDate online = toLocalDate(item.publishedOnline());
        if (online != null) {
            return online;
        }
        LocalDate print = toLocalDate(item.publishedPrint());
        if (print != null) {
            return print;
        }
        LocalDate issued = toLocalDate(item.issued());
        if (issued != null) {
            return issued;
        }
        throw new IllegalArgumentException("Crossref item without any usable date: " + item.doi());
    }

    static LocalDate toLocalDate(CrossrefResponse.DateParts dateParts) {
        if (dateParts == null || dateParts.dateParts().isEmpty()) {
            return null;
        }
        List<Integer> parts = dateParts.dateParts().get(0);
        if (parts == null || parts.isEmpty() || parts.get(0) == null) {
            return null;
        }
        int year = parts.get(0);
        int month = parts.size() > 1 && parts.get(1) != null ? parts.get(1) : 1;
        int day = parts.size() > 2 && parts.get(2) != null ? parts.get(2) : 1;
        try {
            return LocalDate.of(year, month, day);
        } catch (RuntimeException e) {
            // Publishers do send 2024-02-30; degrade to the first of the month rather than lose the
            // record entirely.
            return LocalDate.of(year, Math.min(Math.max(month, 1), 12), 1);
        }
    }
}
