package dev.horizon.ingestion.connector.openalex.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/** One page of {@code /works}, including the cursor for the next one. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAlexPage(Meta meta, List<OpenAlexWork> results) {

    public OpenAlexPage {
        results = results == null ? List.of() : List.copyOf(results);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Meta(
            Integer count,
            @JsonProperty("per_page") Integer perPage,
            @JsonProperty("next_cursor") String nextCursor,
            @JsonProperty("db_response_time_ms") Integer dbResponseTimeMs) {}

    public String nextCursor() {
        return meta == null ? null : meta.nextCursor();
    }
}
