package dev.horizon.ingestion.connector.hackernews.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import dev.horizon.ingestion.domain.document.Provenance;
import dev.horizon.ingestion.domain.port.RawDocument;

/** Ответ поиска Hacker News через Algolia ({@code hn.algolia.com/api/v1/search}). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HackerNewsResponse(List<Hit> hits, Integer page, Integer nbPages) {

    public HackerNewsResponse {
        hits = hits == null ? List.of() : List.copyOf(hits);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Hit(
            @JsonProperty("objectID") String objectId,
            String title,
            String url,
            String author,
            Integer points,
            @JsonProperty("num_comments") Integer numComments,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("story_text") String storyText) {}

    /** Одна история вместе с происхождением ответа, в котором она пришла. */
    public record Raw(Hit hit, String sourceId, String externalId, Provenance provenance) implements RawDocument {}
}
