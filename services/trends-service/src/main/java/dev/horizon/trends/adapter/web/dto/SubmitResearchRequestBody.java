package dev.horizon.trends.adapter.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import dev.horizon.trends.domain.research.TechnologyDomainQuery;

/**
 * Body of {@code POST /api/v1/research-requests}.
 *
 * <p>The length bounds duplicate {@link TechnologyDomainQuery}'s on purpose. The domain check is the
 * real invariant and stays; this one exists so that a malformed request is rejected with a
 * field-level {@code errors[]} entry the UI can attach to the input, instead of a generic message.
 * Two checks, two different jobs.
 */
public record SubmitResearchRequestBody(
        @NotBlank @Size(min = TechnologyDomainQuery.MIN_LENGTH, max = TechnologyDomainQuery.MAX_LENGTH) String query,
        @Valid AnalysisParametersDto parameters,
        Boolean refresh) {

    /**
     * Whether the analyst asked for a recomputation rather than an answer.
     *
     * <p>Absent means no: forcing work is an explicit act, and a default that recomputed would make
     * every ordinary question cost a full analysis.
     */
    public boolean forceRefresh() {
        return Boolean.TRUE.equals(refresh);
    }
}
