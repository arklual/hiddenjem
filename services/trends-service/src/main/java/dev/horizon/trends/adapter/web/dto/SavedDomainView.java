package dev.horizon.trends.adapter.web.dto;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import dev.horizon.trends.domain.report.TrendReportId;
import dev.horizon.trends.domain.saveddomain.SavedDomain;

/** OpenAPI {@code SavedDomain}. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SavedDomainView(
        UUID id,
        String query,
        String normalizedQuery,
        AnalysisParametersDto parameters,
        UUID lastReportId,
        Instant createdAt) {

    public static SavedDomainView from(SavedDomain domain) {
        return new SavedDomainView(
                domain.id(),
                domain.query().raw(),
                domain.query().normalized(),
                AnalysisParametersDto.from(domain.parameters()),
                domain.lastReport().map(TrendReportId::value).orElse(null),
                domain.createdAt());
    }
}
