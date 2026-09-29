package dev.horizon.trends.domain.saveddomain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import dev.horizon.platform.common.id.Uuid7;
import dev.horizon.platform.common.util.Guards;
import dev.horizon.trends.domain.report.TrendReportId;
import dev.horizon.trends.domain.research.AnalysisParameters;
import dev.horizon.trends.domain.research.TechnologyDomainQuery;

/** A direction the analyst tracks over time (JTBD-5, UC-B). */
public record SavedDomain(
        UUID id,
        UUID userId,
        TechnologyDomainQuery query,
        AnalysisParameters parameters,
        TrendReportId lastReportId,
        Instant createdAt) {

    /** Guard-rail against unbounded per-user growth; also keeps the sidebar usable. */
    public static final int MAX_PER_USER = 50;

    public SavedDomain {
        Guards.requireNonNull(id, "savedDomain.id");
        Guards.requireNonNull(userId, "savedDomain.userId");
        Guards.requireNonNull(query, "savedDomain.query");
        Guards.requireNonNull(parameters, "savedDomain.parameters");
        Guards.requireNonNull(createdAt, "savedDomain.createdAt");
    }

    public static SavedDomain create(
            UUID userId, TechnologyDomainQuery query, AnalysisParameters parameters, Instant now) {
        return new SavedDomain(Uuid7.randomUuid7(), userId, query, parameters, null, now);
    }

    public SavedDomain withLastReport(TrendReportId reportId) {
        return new SavedDomain(id, userId, query, parameters, reportId, createdAt);
    }

    public Optional<TrendReportId> lastReport() {
        return Optional.ofNullable(lastReportId);
    }
}
