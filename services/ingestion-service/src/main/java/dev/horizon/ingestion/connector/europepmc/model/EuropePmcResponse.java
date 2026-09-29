package dev.horizon.ingestion.connector.europepmc.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import dev.horizon.ingestion.domain.document.Provenance;
import dev.horizon.ingestion.domain.port.RawDocument;

/** Ответ поиска Europe PMC ({@code /webservices/rest/search}, {@code resultType=core}). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EuropePmcResponse(Integer hitCount, String nextCursorMark, ResultList resultList) {

    public List<Result> results() {
        return resultList == null ? List.of() : resultList.result();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ResultList(List<Result> result) {
        public ResultList {
            result = result == null ? List.of() : List.copyOf(result);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(
            String id,
            String source,
            String doi,
            String title,
            String abstractText,
            String firstPublicationDate,
            String pubYear,
            String language,
            Integer citedByCount,
            JournalInfo journalInfo,
            AuthorList authorList) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record JournalInfo(Journal journal) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Journal(String title, String issn) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AuthorList(List<PmcAuthor> author) {
        public AuthorList {
            author = author == null ? List.of() : List.copyOf(author);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PmcAuthor(String fullName, AffiliationList authorAffiliationDetailsList) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AffiliationList(List<Affiliation> authorAffiliation) {
        public AffiliationList {
            authorAffiliation = authorAffiliation == null ? List.of() : List.copyOf(authorAffiliation);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Affiliation(String affiliation) {}

    /** Одна запись вместе с происхождением ответа, в котором она пришла. */
    public record Raw(Result result, String sourceId, String externalId, Provenance provenance)
            implements RawDocument {}
}
