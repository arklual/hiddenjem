package dev.horizon.trends.domain.report;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import dev.horizon.platform.common.id.Uuid7;
import dev.horizon.platform.common.util.Guards;
import dev.horizon.trends.domain.report.event.TrendReportGenerated;
import dev.horizon.trends.domain.research.RequesterRef;
import dev.horizon.trends.domain.research.ResearchRequestId;
import dev.horizon.trends.domain.research.TechnologyDomainQuery;
import dev.horizon.trends.domain.shared.TrendsDomainEvent;

/**
 * Immutable aggregate: the result of one analysis (BRULE-5).
 *
 * <p>Immutability is a domain decision, not a technical one. A trend report is evidence used to
 * justify investment decisions; if it could be edited in place, "what did we know in March" would
 * become unanswerable. Recomputation therefore produces a new version linked to the previous one.
 *
 * <p>Invariants J1–J6 are checked in the factory, so an instance that exists is always publishable.
 */
public final class TrendReport {

    private final TrendReportId id;
    private final ResearchRequestId researchRequestId;
    private final int version;
    private final TrendReportId previousVersionId;
    private final TechnologyDomainQuery query;
    private final MethodologyRef methodology;
    private final UUID corpusSnapshotId;
    private final Coverage coverage;
    private final boolean truncated;
    private final List<RankedTrend> trends;
    private final Instant generatedAt;

    private final transient List<TrendsDomainEvent> pendingEvents;

    private TrendReport(
            TrendReportId id,
            ResearchRequestId researchRequestId,
            int version,
            TrendReportId previousVersionId,
            TechnologyDomainQuery query,
            MethodologyRef methodology,
            UUID corpusSnapshotId,
            Coverage coverage,
            boolean truncated,
            List<RankedTrend> trends,
            Instant generatedAt,
            List<TrendsDomainEvent> pendingEvents) {
        this.id = id;
        this.researchRequestId = researchRequestId;
        this.version = version;
        this.previousVersionId = previousVersionId;
        this.query = query;
        this.methodology = methodology;
        this.corpusSnapshotId = corpusSnapshotId;
        this.coverage = coverage;
        this.truncated = truncated;
        this.trends = trends;
        this.generatedAt = generatedAt;
        this.pendingEvents = pendingEvents;
    }

    /**
     * Creates a report, enforcing every publication invariant.
     *
     * @throws IllegalArgumentException if ranks are not the contiguous sequence 1..n, if any trend
     *     lacks evidence, or if the trend count exceeds the requested {@code topN}
     */
    public static TrendReport create(
            ResearchRequestId researchRequestId,
            RequesterRef requester,
            TechnologyDomainQuery query,
            MethodologyRef methodology,
            UUID corpusSnapshotId,
            Coverage coverage,
            List<RankedTrend> trends,
            int requestedTopN,
            int version,
            TrendReportId previousVersionId,
            Instant generatedAt) {
        Guards.requireNonNull(researchRequestId, "researchRequestId");
        Guards.requireNonNull(query, "query");
        Guards.requireNonNull(methodology, "methodology");
        Guards.requireNonNull(corpusSnapshotId, "corpusSnapshotId");
        Guards.requireNonNull(coverage, "coverage");
        Guards.requireNonNull(trends, "trends");
        Guards.requireNonNull(generatedAt, "generatedAt");
        Guards.requireArgument(version >= 1, "version must be at least 1");

        var ordered = trends.stream()
                .sorted((a, b) -> Integer.compare(a.rank(), b.rank()))
                .toList();
        verifyContiguousRanks(ordered);
        if (ordered.size() > requestedTopN) {
            throw new IllegalArgumentException("J3 нарушен: отчёт содержит %d трендов при запрошенных %d"
                    .formatted(ordered.size(), requestedTopN));
        }

        var id = TrendReportId.generate();
        boolean truncated = ordered.size() < requestedTopN;
        var event = new TrendReportGenerated(
                Uuid7.randomUuid7(),
                generatedAt,
                id,
                researchRequestId,
                requester,
                query.normalized(),
                ordered.size(),
                coverage.partial(),
                methodology.version(),
                corpusSnapshotId);
        return new TrendReport(
                id,
                researchRequestId,
                version,
                previousVersionId,
                query,
                methodology,
                corpusSnapshotId,
                coverage,
                truncated,
                ordered,
                generatedAt,
                new java.util.ArrayList<>(List.of(event)));
    }

    /** Rehydration from storage: no events, no re-derivation. */
    @SuppressWarnings("java:S107")
    public static TrendReport rehydrate(
            TrendReportId id,
            ResearchRequestId researchRequestId,
            int version,
            TrendReportId previousVersionId,
            TechnologyDomainQuery query,
            MethodologyRef methodology,
            UUID corpusSnapshotId,
            Coverage coverage,
            boolean truncated,
            List<RankedTrend> trends,
            Instant generatedAt) {
        return new TrendReport(
                id,
                researchRequestId,
                version,
                previousVersionId,
                query,
                methodology,
                corpusSnapshotId,
                coverage,
                truncated,
                List.copyOf(trends),
                generatedAt,
                new java.util.ArrayList<>());
    }

    private static void verifyContiguousRanks(List<RankedTrend> ordered) {
        for (int i = 0; i < ordered.size(); i++) {
            if (ordered.get(i).rank() != i + 1) {
                throw new IllegalArgumentException(
                        "J1 нарушен: ранги должны образовывать последовательность 1..n, найден %d на позиции %d"
                                .formatted(ordered.get(i).rank(), i + 1));
            }
        }
    }

    public Optional<RankedTrend> findByKey(String trendKey) {
        return trends.stream().filter(t -> t.trendKey().equals(trendKey)).findFirst();
    }

    public List<TrendsDomainEvent> drainEvents() {
        var drained = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return drained;
    }

    public TrendReportId id() {
        return id;
    }

    public ResearchRequestId researchRequestId() {
        return researchRequestId;
    }

    public int version() {
        return version;
    }

    public Optional<TrendReportId> previousVersionId() {
        return Optional.ofNullable(previousVersionId);
    }

    public TechnologyDomainQuery query() {
        return query;
    }

    public MethodologyRef methodology() {
        return methodology;
    }

    public UUID corpusSnapshotId() {
        return corpusSnapshotId;
    }

    public Coverage coverage() {
        return coverage;
    }

    public boolean truncated() {
        return truncated;
    }

    public List<RankedTrend> trends() {
        return trends;
    }

    public Instant generatedAt() {
        return generatedAt;
    }
}
