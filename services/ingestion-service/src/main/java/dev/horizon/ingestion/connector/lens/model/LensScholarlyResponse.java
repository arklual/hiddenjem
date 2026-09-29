package dev.horizon.ingestion.connector.lens.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import dev.horizon.ingestion.domain.document.Provenance;
import dev.horizon.ingestion.domain.port.RawDocument;

/** Ответ поиска научных работ Lens ({@code POST /scholarly/search}). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LensScholarlyResponse(Integer total, Integer results, List<Work> data) {

    public LensScholarlyResponse {
        data = data == null ? List.of() : List.copyOf(data);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Work(
            @JsonProperty("lens_id") String lensId,
            String title,
            // Поле называется `abstract` — в Java это ключевое слово.
            @JsonProperty("abstract") String abstractText,
            @JsonProperty("date_published") String datePublished,
            @JsonProperty("year_published") Integer yearPublished,
            @JsonProperty("publication_type") String publicationType,
            Source source,
            List<Author> authors,
            @JsonProperty("external_ids") List<ExternalId> externalIds,
            @JsonProperty("scholarly_citations_count") Integer scholarlyCitationsCount,
            List<String> languages) {

        public Work {
            authors = authors == null ? List.of() : List.copyOf(authors);
            externalIds = externalIds == null ? List.of() : List.copyOf(externalIds);
            languages = languages == null ? List.of() : List.copyOf(languages);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Source(String title, String type, String publisher) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Author(
            @JsonProperty("first_name") String firstName,
            @JsonProperty("last_name") String lastName,
            List<ExternalId> ids,
            List<Affiliation> affiliations) {

        public Author {
            ids = ids == null ? List.of() : List.copyOf(ids);
            affiliations = affiliations == null ? List.of() : List.copyOf(affiliations);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Affiliation(String name, @JsonProperty("country_code") String countryCode) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExternalId(String type, String value) {}

    /** Одна работа вместе с происхождением ответа, в котором она пришла. */
    public record Raw(Work work, String sourceId, String externalId, Provenance provenance) implements RawDocument {}
}
