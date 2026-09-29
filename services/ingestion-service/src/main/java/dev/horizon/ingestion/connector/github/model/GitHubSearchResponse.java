package dev.horizon.ingestion.connector.github.model;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import dev.horizon.ingestion.domain.document.Provenance;
import dev.horizon.ingestion.domain.port.RawDocument;

/**
 * GitHub repository-search response.
 *
 * <p>Repositories are the adoption signal: a technology that people are actually building with shows
 * up as code long before it shows up in a journal. Stars and forks are kept as raw counts — the
 * methodology decides what they are worth, the connector only reports them.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubSearchResponse(
        @JsonProperty("total_count") Integer totalCount,
        @JsonProperty("incomplete_results") Boolean incompleteResults,
        List<Repository> items) {

    public GitHubSearchResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Repository(
            Long id,
            String name,
            @JsonProperty("full_name") String fullName,
            @JsonProperty("html_url") String htmlUrl,
            String description,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("pushed_at") String pushedAt,
            @JsonProperty("stargazers_count") Integer stargazersCount,
            @JsonProperty("forks_count") Integer forksCount,
            @JsonProperty("open_issues_count") Integer openIssuesCount,
            String language,
            List<String> topics,
            Owner owner,
            License license) {

        public Repository {
            topics = topics == null ? List.of() : List.copyOf(topics);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Owner(String login, String type, @JsonProperty("html_url") String htmlUrl) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record License(@JsonProperty("spdx_id") String spdxId, String name) {}

    /** One repository paired with the provenance of the response it came in. */
    public record Raw(Repository repository, String sourceId, String externalId, Provenance provenance)
            implements RawDocument {}
}
