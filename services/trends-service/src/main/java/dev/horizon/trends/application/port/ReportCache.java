package dev.horizon.trends.application.port;

import java.util.Optional;

import dev.horizon.trends.domain.report.TrendReport;
import dev.horizon.trends.domain.report.TrendReportId;

/**
 * Read-through cache for finished reports.
 *
 * <p>Reports are immutable, which makes them ideal cache entries: there is no invalidation problem,
 * only expiry. Every method must degrade to a no-op on backend failure — a cache outage may slow the
 * system down but must never make it incorrect or unavailable (ADR-0011).
 */
public interface ReportCache {

    Optional<TrendReport> get(TrendReportId id);

    void put(TrendReport report);

    void evict(TrendReportId id);
}
