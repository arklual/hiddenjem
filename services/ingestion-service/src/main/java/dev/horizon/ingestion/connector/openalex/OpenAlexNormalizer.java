package dev.horizon.ingestion.connector.openalex;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import dev.horizon.ingestion.connector.openalex.model.OpenAlexWork;
import dev.horizon.ingestion.domain.document.Author;
import dev.horizon.ingestion.domain.document.Document;
import dev.horizon.ingestion.domain.document.DocumentMetrics;
import dev.horizon.ingestion.domain.document.DocumentTopic;
import dev.horizon.ingestion.domain.document.OrganizationType;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.document.Venue;
import dev.horizon.ingestion.domain.port.DocumentNormalizer;

/**
 * ACL for OpenAlex.
 *
 * <p>The most substantial translation in the service, because OpenAlex's model is the richest:
 *
 * <ul>
 *   <li><b>The abstract is reconstructed</b> from {@code abstract_inverted_index}. OpenAlex
 *       publishes a word → positions map rather than text; without inversion every OpenAlex document
 *       would reach the analysis engine with an empty abstract and contribute almost nothing to term
 *       extraction. This single method is the reason the connector is worth having.
 *   <li><b>Institutions become author affiliations</b> with a country and a canonical organisation
 *       type, mapping OpenAlex's vocabulary ({@code education}, {@code facility}) onto ours.
 *   <li><b>Work type decides the source class</b>: a preprint stays a preprint even though it
 *       arrives through a bibliographic index, because its evidential weight differs.
 *   <li><b>Concepts become raw topics</b> with their scores — a prior for the analysis, never a
 *       result of it.
 * </ul>
 */
public class OpenAlexNormalizer implements DocumentNormalizer<OpenAlexWork.Raw> {

    @Override
    public Document normalize(OpenAlexWork.Raw raw) {
        OpenAlexWork work = raw.work();
        var builder = Document.builder()
                .externalRef(raw.sourceId(), raw.externalId())
                .sourceClass(sourceClassOf(work))
                .title(titleOf(work))
                .abstractText(reconstructAbstract(work.abstractInvertedIndex()))
                .language(work.language())
                .publishedOn(publicationDateOf(work))
                .doi(work.doi())
                .url(urlOf(work, raw.externalId()))
                .venue(venueOf(work))
                .metrics(DocumentMetrics.ofCitations(work.citedByCount()))
                .provenance(raw.provenance());

        for (OpenAlexWork.Authorship authorship : work.authorships()) {
            builder.author(authorOf(authorship));
        }
        for (OpenAlexWork.Concept concept : work.concepts()) {
            if (concept.displayName() != null) {
                builder.topic(DocumentTopic.of(codeOf(concept), concept.displayName(), concept.score()));
            }
        }
        return builder.build();
    }

    /**
     * Rebuilds abstract text from OpenAlex's inverted index.
     *
     * <p>{@code {"Deep": [0], "learning": [1, 7]}} → {@code "Deep learning ..."}. Positions are
     * sparse in practice (OpenAlex omits some tokens), so gaps are simply skipped rather than filled
     * with placeholders — a readable abstract with a missing stop-word is far more useful to term
     * extraction than one peppered with markers.
     */
    static String reconstructAbstract(Map<String, List<Integer>> invertedIndex) {
        if (invertedIndex == null || invertedIndex.isEmpty()) {
            return null;
        }
        int maxPosition = -1;
        for (List<Integer> positions : invertedIndex.values()) {
            if (positions == null) {
                continue;
            }
            for (Integer position : positions) {
                if (position != null && position > maxPosition) {
                    maxPosition = position;
                }
            }
        }
        if (maxPosition < 0) {
            return null;
        }
        String[] words = new String[maxPosition + 1];
        for (Map.Entry<String, List<Integer>> entry : invertedIndex.entrySet()) {
            if (entry.getValue() == null) {
                continue;
            }
            for (Integer position : entry.getValue()) {
                if (position != null && position >= 0 && position <= maxPosition) {
                    words[position] = entry.getKey();
                }
            }
        }
        var text = new StringBuilder();
        for (String word : words) {
            if (word == null) {
                continue;
            }
            if (text.length() > 0) {
                text.append(' ');
            }
            text.append(word);
        }
        String result = text.toString().trim();
        return result.isEmpty() ? null : result;
    }

    private static Author authorOf(OpenAlexWork.Authorship authorship) {
        String name = authorship.author() == null ? null : authorship.author().displayName();
        if (name == null || name.isBlank()) {
            // A nameless authorship happens for consortium entries; keeping the slot preserves the
            // author count, which the dedup key and the evidence list both rely on.
            name = "Unknown";
        }
        String orcid = authorship.author() == null ? null : authorship.author().orcid();
        OpenAlexWork.Institution institution = authorship.institutions().isEmpty()
                ? null
                : authorship.institutions().get(0);
        String organizationName = institution != null
                ? institution.displayName()
                : (authorship.rawAffiliationStrings().isEmpty()
                        ? null
                        : authorship.rawAffiliationStrings().get(0));
        OrganizationType organizationType = institution == null
                ? null
                : OrganizationType.parse(institution.type()).orElse(null);
        String country = institution == null ? null : institution.countryCode();
        return new Author(name, orcid, organizationName, organizationType, country);
    }

    private static SourceClass sourceClassOf(OpenAlexWork work) {
        String type = work.type() == null ? "" : work.type().toLowerCase(Locale.ROOT);
        String hostType =
                work.primaryLocation() != null && work.primaryLocation().source() != null
                        ? String.valueOf(work.primaryLocation().source().type()).toLowerCase(Locale.ROOT)
                        : "";
        if (type.contains("preprint") || "repository".equals(hostType)) {
            return SourceClass.PREPRINT;
        }
        if (type.contains("standard")) {
            return SourceClass.STANDARD;
        }
        if (type.contains("report")) {
            return SourceClass.ANALYST_REPORT;
        }
        return SourceClass.JOURNAL_ARTICLE;
    }

    private static String titleOf(OpenAlexWork work) {
        String title = work.title() == null || work.title().isBlank() ? work.displayName() : work.title();
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("OpenAlex work without a title: " + work.id());
        }
        return title;
    }

    private static LocalDate publicationDateOf(OpenAlexWork work) {
        if (work.publicationDate() != null && !work.publicationDate().isBlank()) {
            return LocalDate.parse(work.publicationDate().trim());
        }
        if (work.publicationYear() != null) {
            // Year-only records exist; anchoring them to 1 January keeps them inside the right
            // annual partition and the right analysis period.
            return LocalDate.of(work.publicationYear(), 1, 1);
        }
        throw new IllegalArgumentException("OpenAlex work without a publication date: " + work.id());
    }

    private static Venue venueOf(OpenAlexWork work) {
        var location = work.primaryLocation() != null ? work.primaryLocation() : work.bestOaLocation();
        if (location == null || location.source() == null) {
            return null;
        }
        var source = location.source();
        return Venue.orNull(source.displayName(), source.type(), source.issnL());
    }

    private static String urlOf(OpenAlexWork work, String externalId) {
        var location = work.primaryLocation() != null ? work.primaryLocation() : work.bestOaLocation();
        if (location != null
                && location.landingPageUrl() != null
                && !location.landingPageUrl().isBlank()) {
            return location.landingPageUrl();
        }
        if (work.doi() != null && !work.doi().isBlank()) {
            return work.doi().startsWith("http") ? work.doi() : "https://doi.org/" + work.doi();
        }
        return work.id() != null ? work.id() : "https://openalex.org/" + externalId;
    }

    /** OpenAlex concept ids are URLs; the trailing identifier is what fits {@code varchar(64)}. */
    private static String codeOf(OpenAlexWork.Concept concept) {
        String id = concept.id();
        if (id == null || id.isBlank()) {
            return concept.displayName();
        }
        int slash = id.lastIndexOf('/');
        return slash >= 0 && slash < id.length() - 1 ? id.substring(slash + 1) : id;
    }

    /** Kept package-visible for the contract test to assert institution mapping in isolation. */
    static List<String> affiliationsOf(OpenAlexWork work) {
        List<String> names = new ArrayList<>();
        for (OpenAlexWork.Authorship authorship : work.authorships()) {
            for (OpenAlexWork.Institution institution : authorship.institutions()) {
                if (institution.displayName() != null) {
                    names.add(institution.displayName());
                }
            }
        }
        return names;
    }
}
