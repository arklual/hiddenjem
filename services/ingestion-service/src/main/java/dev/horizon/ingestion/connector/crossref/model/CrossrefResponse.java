package dev.horizon.ingestion.connector.crossref.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import dev.horizon.ingestion.domain.document.Provenance;
import dev.horizon.ingestion.domain.port.RawDocument;

/**
 * Crossref's {@code /works} envelope.
 *
 * <p>Crossref's model is idiosyncratic in ways the normalizer has to absorb: titles are
 * <em>arrays</em>, dates are arrays of {@code [year, month, day]} integer parts with month and day
 * optional, the abstract is a blob of JATS XML, and the DOI key is upper case. Every one of those is
 * kept verbatim here so the translation lives in exactly one reviewable place.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CrossrefResponse(String status, Message message) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Message(
            @JsonProperty("total-results") Integer totalResults,
            @JsonProperty("next-cursor") String nextCursor,
            @JsonProperty("items-per-page") Integer itemsPerPage,
            List<Item> items) {

        public Message {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(
            @JsonProperty("DOI") String doi,
            @JsonProperty("URL") String url,
            List<String> title,
            @JsonProperty("abstract") String abstractText,
            String type,
            String language,
            String publisher,
            @JsonProperty("container-title") List<String> containerTitle,
            @JsonProperty("ISSN") List<String> issn,
            @JsonProperty("is-referenced-by-count") Integer referencedByCount,
            @JsonProperty("references-count") Integer referencesCount,
            List<String> subject,
            List<Author> author,
            DateParts issued,
            @JsonProperty("published-online") DateParts publishedOnline,
            @JsonProperty("published-print") DateParts publishedPrint) {

        public Item {
            title = title == null ? List.of() : List.copyOf(title);
            containerTitle = containerTitle == null ? List.of() : List.copyOf(containerTitle);
            issn = issn == null ? List.of() : List.copyOf(issn);
            subject = subject == null ? List.of() : List.copyOf(subject);
            author = author == null ? List.of() : List.copyOf(author);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Author(
            String given,
            String family,
            String name,
            @JsonProperty("ORCID") String orcid,
            String sequence,
            List<Affiliation> affiliation) {

        public Author {
            affiliation = affiliation == null ? List.of() : List.copyOf(affiliation);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Affiliation(String name) {}

    /** {@code {"date-parts": [[2024, 5, 1]]}} — month and day may be absent. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DateParts(@JsonProperty("date-parts") List<List<Integer>> dateParts) {

        public DateParts {
            dateParts = dateParts == null ? List.of() : List.copyOf(dateParts);
        }
    }

    /** One item paired with the provenance of the response it came in. */
    public record Raw(Item item, String sourceId, String externalId, Provenance provenance) implements RawDocument {}
}
