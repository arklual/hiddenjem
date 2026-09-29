package dev.horizon.ingestion.connector.semanticscholar.model;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import dev.horizon.ingestion.domain.document.Provenance;
import dev.horizon.ingestion.domain.port.RawDocument;

/** Ответ массового поиска Semantic Scholar ({@code /graph/v1/paper/search/bulk}). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SemanticScholarResponse(Integer total, String token, List<Paper> data) {

    public SemanticScholarResponse {
        data = data == null ? List.of() : List.copyOf(data);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Paper(
            String paperId,
            Map<String, Object> externalIds,
            String url,
            String title,
            // Поле называется `abstract` — в Java это ключевое слово.
            @JsonProperty("abstract") String abstractText,
            String venue,
            Integer year,
            Integer citationCount,
            String publicationDate,
            List<String> publicationTypes,
            List<String> fieldsOfStudy,
            List<PaperAuthor> authors) {

        public Paper {
            // Значения бывают null («DBLP»: null), а Map.copyOf их не принимает.
            externalIds = externalIds == null
                    ? Map.of()
                    : externalIds.entrySet().stream()
                            .filter(entry -> entry.getKey() != null && entry.getValue() != null)
                            .collect(java.util.stream.Collectors.toUnmodifiableMap(
                                    Map.Entry::getKey, Map.Entry::getValue));
            publicationTypes = publicationTypes == null ? List.of() : List.copyOf(publicationTypes);
            fieldsOfStudy = fieldsOfStudy == null ? List.of() : List.copyOf(fieldsOfStudy);
            authors = authors == null ? List.of() : List.copyOf(authors);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaperAuthor(String authorId, String name) {}

    /** Одна работа вместе с происхождением ответа, в котором она пришла. */
    public record Raw(Paper paper, String sourceId, String externalId, Provenance provenance)
            implements RawDocument {}
}
