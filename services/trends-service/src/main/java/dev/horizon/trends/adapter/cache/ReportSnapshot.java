package dev.horizon.trends.adapter.cache;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import dev.horizon.trends.domain.report.Coverage;
import dev.horizon.trends.domain.report.MethodologyRef;
import dev.horizon.trends.domain.report.RankedTrend;
import dev.horizon.trends.domain.report.TrendReport;
import dev.horizon.trends.domain.report.TrendReportId;
import dev.horizon.trends.domain.research.ResearchRequestId;
import dev.horizon.trends.domain.research.TechnologyDomainQuery;

/**
 * Serialisable form of {@link TrendReport} for the cache.
 *
 * <p>{@code TrendReport} is a proper aggregate — private constructor, static factories that enforce
 * invariants, an internal event list — so Jackson cannot and should not construct one directly. This
 * record is the explicit boundary: it flattens the aggregate for transport and rebuilds it through
 * {@link TrendReport#rehydrate}, which is the same door the database repository uses. A cached entry
 * therefore goes through exactly the same reconstruction as a stored one.
 *
 * <p>The nested value objects are reused as-is: they are plain records whose JSON already matches
 * {@code contracts/schemas/}, and duplicating them here would create a second shape to keep in sync.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReportSnapshot(
        UUID id,
        UUID researchRequestId,
        int version,
        UUID previousVersionId,
        String rawQuery,
        String normalizedQuery,
        String queryLanguage,
        MethodologyRef methodology,
        UUID corpusSnapshotId,
        Coverage coverage,
        boolean truncated,
        List<RankedTrend> trends,
        Instant generatedAt) {

    public static ReportSnapshot from(TrendReport report) {
        return new ReportSnapshot(
                report.id().value(),
                report.researchRequestId().value(),
                report.version(),
                report.previousVersionId().map(TrendReportId::value).orElse(null),
                report.query().raw(),
                report.query().normalized(),
                report.query().language(),
                report.methodology(),
                report.corpusSnapshotId(),
                report.coverage(),
                report.truncated(),
                report.trends(),
                report.generatedAt());
    }

    public TrendReport toDomain() {
        return TrendReport.rehydrate(
                new TrendReportId(id),
                new ResearchRequestId(researchRequestId),
                version,
                previousVersionId == null ? null : new TrendReportId(previousVersionId),
                new TechnologyDomainQuery(rawQuery, normalizedQuery, queryLanguage),
                methodology,
                corpusSnapshotId,
                coverage,
                truncated,
                trends,
                generatedAt);
    }
}
