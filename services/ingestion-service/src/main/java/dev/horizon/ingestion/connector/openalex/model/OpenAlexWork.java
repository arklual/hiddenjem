package dev.horizon.ingestion.connector.openalex.model;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import dev.horizon.ingestion.domain.document.Provenance;
import dev.horizon.ingestion.domain.port.RawDocument;

/**
 * A work as OpenAlex describes it.
 *
 * <p>OpenAlex is the richest of the sources — it is the only one that reliably reports
 * <em>institutions</em> with country and type, which is what the diffusion indicator ("how many
 * distinct kinds of organisation touch this topic") is computed from.
 *
 * <p>It also has the strangest field in the whole system: {@code abstract_inverted_index}, a
 * word → positions map published instead of the abstract text for licensing reasons. Reconstructing
 * it is the normalizer's job; the raw model keeps it exactly as received.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAlexWork(
        String id,
        String doi,
        String title,
        @JsonProperty("display_name") String displayName,
        @JsonProperty("publication_date") String publicationDate,
        @JsonProperty("publication_year") Integer publicationYear,
        String language,
        String type,
        @JsonProperty("cited_by_count") Integer citedByCount,
        @JsonProperty("abstract_inverted_index") Map<String, List<Integer>> abstractInvertedIndex,
        @JsonProperty("primary_location") Location primaryLocation,
        @JsonProperty("best_oa_location") Location bestOaLocation,
        List<Authorship> authorships,
        List<Concept> concepts) {

    public OpenAlexWork {
        authorships = authorships == null ? List.of() : List.copyOf(authorships);
        concepts = concepts == null ? List.of() : List.copyOf(concepts);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Location(
            @JsonProperty("landing_page_url") String landingPageUrl,
            @JsonProperty("pdf_url") String pdfUrl,
            @JsonProperty("is_oa") Boolean isOa,
            OpenAlexSource source) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OpenAlexSource(
            String id,
            @JsonProperty("display_name") String displayName,
            @JsonProperty("issn_l") String issnL,
            String type,
            @JsonProperty("host_organization_name") String hostOrganizationName) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Authorship(
            @JsonProperty("author_position") String authorPosition,
            Author author,
            List<Institution> institutions,
            @JsonProperty("raw_affiliation_strings") List<String> rawAffiliationStrings) {

        public Authorship {
            institutions = institutions == null ? List.of() : List.copyOf(institutions);
            rawAffiliationStrings = rawAffiliationStrings == null ? List.of() : List.copyOf(rawAffiliationStrings);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Author(String id, @JsonProperty("display_name") String displayName, String orcid) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Institution(
            String id,
            @JsonProperty("display_name") String displayName,
            @JsonProperty("country_code") String countryCode,
            String type) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Concept(String id, @JsonProperty("display_name") String displayName, Double score, Integer level) {}

    /** The work paired with the provenance of the response it arrived in. */
    public record Raw(OpenAlexWork work, String sourceId, String externalId, Provenance provenance)
            implements RawDocument {}
}
