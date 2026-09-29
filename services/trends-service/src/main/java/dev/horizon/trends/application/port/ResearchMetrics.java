package dev.horizon.trends.application.port;

/**
 * Counters for what the research process produced.
 *
 * <p>A port rather than a direct {@code MeterRegistry} call for the usual reason — the saga is
 * testable without a metrics backend — and for one specific to this project: the alerting rules in
 * {@code deploy/compose/observability/alerts.yml} referred to {@code horizon_research_requests_total}
 * and {@code horizon_trends_produced_total} for weeks while nothing emitted them. An alert on a
 * metric nobody publishes never fires; it looks like monitoring and is silence. Naming the
 * measurement in the application layer puts the obligation where the process lives, instead of
 * leaving it to whoever remembers to instrument an adapter.
 */
public interface ResearchMetrics {

    /**
     * A report reached the analyst.
     *
     * @param trendCount how many trends the report carries
     * @param partial whether some sources were unavailable when the corpus was collected — the
     *     ratio of partial to total runs is what tells an operator that a source is failing while
     *     every request still succeeds
     */
    void reportPublished(int trendCount, boolean partial);

    /** A request ended without a report — the saga failed it or its deadline passed. */
    void requestFailed();

    /** A request was cancelled by the analyst who started it. */
    void requestCancelled();
}
