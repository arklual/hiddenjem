package dev.horizon.trends.adapter.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import dev.horizon.trends.domain.research.TechnologyDomainQuery;

/** Body of {@code POST /api/v1/saved-domains}. */
public record SaveDomainRequestBody(
        @NotBlank @Size(min = TechnologyDomainQuery.MIN_LENGTH, max = TechnologyDomainQuery.MAX_LENGTH) String query,
        @Valid AnalysisParametersDto parameters) {}
