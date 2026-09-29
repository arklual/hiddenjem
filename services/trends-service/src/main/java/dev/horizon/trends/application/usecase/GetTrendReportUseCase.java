package dev.horizon.trends.application.usecase;

import java.util.Collection;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.horizon.platform.common.error.HorizonException;
import dev.horizon.trends.application.port.ReportCache;
import dev.horizon.trends.application.port.ResearchRequestRepository;
import dev.horizon.trends.application.port.TrendReportRepository;
import dev.horizon.trends.domain.report.ReportDelta;
import dev.horizon.trends.domain.report.TrendReport;
import dev.horizon.trends.domain.report.TrendReportId;
import dev.horizon.trends.domain.research.ReportViewer;

/**
 * Reads a report, cache first (UC-04, UC-05).
 *
 * <p>Reports are immutable, so a cache hit can be served without any freshness check — the single
 * biggest reason the read path meets its 300 ms p95 budget (NFR-P1).
 */
@Service
@Transactional(readOnly = true)
public class GetTrendReportUseCase {

    private final TrendReportRepository reports;
    private final ResearchRequestRepository requests;
    private final ReportCache cache;

    public GetTrendReportUseCase(TrendReportRepository reports, ResearchRequestRepository requests, ReportCache cache) {
        this.reports = reports;
        this.requests = requests;
        this.cache = cache;
    }

    /**
     * Человеческие названия тем по их ключам — для списка скрытого по пометке аналитика.
     *
     * <p>Здесь, а не в контроллере с собственным портом: контроллер и так знает сценарий чтения
     * отчёта, а лишняя зависимость у него потребовала бы бина в каждом веб-срезе, включая чужие.
     * Прикладной слой уже держит нужный порт.
     */
    public Map<String, String> titlesFor(String normalizedQuery, Collection<String> trendKeys) {
        return reports.titlesByTrendKey(normalizedQuery, trendKeys);
    }

    public TrendReport get(TrendReportId id, ReportViewer viewer) {
        var report = cache.get(id).orElseGet(() -> {
            var loaded = reports.findById(id).orElseThrow(() -> HorizonException.notFound("Отчёт", id));
            cache.put(loaded);
            return loaded;
        });
        authorize(report, viewer);
        return report;
    }

    /**
     * What changed since the previous version of the same report (UC-12).
     *
     * <p>The previous version belongs to a different request — the lineage of a direction spans
     * every analysis of it, by whoever ran it. So its visibility is checked here, by the same rule
     * as any other read, and a version the caller may not see is reported as nothing to compare
     * with rather than refused: the question was about *this* report.
     */
    @Transactional(readOnly = true)
    public ReportDelta delta(TrendReportId id, ReportViewer viewer) {
        var current = get(id, viewer);
        var previousId = current.previousVersionId().orElse(null);
        if (previousId == null) {
            return ReportDelta.unavailable(ReportDelta.Unavailable.NO_PREVIOUS_VERSION);
        }
        // A link to a version that retention has already removed is "nothing to compare with", not a
        // failed request. Letting `get` throw here would answer a question about *this* report with a
        // 404 naming an id the caller never asked for.
        //
        // The same for a version the caller may not read. The lineage of a direction is shared
        // between users, so the predecessor can belong to someone else's request — and the report it
        // points at must not leak its topics, keys and scores through this endpoint. "Not yours" is
        // answered the same way as "no longer there": there is nothing here to compare with.
        return reports.findById(previousId)
                .filter(previous -> isVisible(previous, viewer))
                .map(previous -> ReportDelta.between(current, previous))
                .orElseGet(() -> ReportDelta.unavailable(ReportDelta.Unavailable.NO_PREVIOUS_VERSION));
    }

    /**
     * A report inherits the visibility of the request that produced it; there is no separate ACL to
     * drift out of sync.
     */
    /** The same rule as {@link #authorize}, as a question rather than an assertion. */
    private boolean isVisible(TrendReport report, ReportViewer viewer) {
        try {
            authorize(report, viewer);
            return true;
        } catch (HorizonException denied) {
            return false;
        }
    }

    private void authorize(TrendReport report, ReportViewer viewer) {
        if (viewer.administrator()) {
            return;
        }
        var owner = requests.findById(report.researchRequestId())
                .orElseThrow(() -> HorizonException.notFound("Отчёт", report.id()));
        if (!owner.isVisibleTo(viewer)) {
            throw HorizonException.notFound("Отчёт", report.id());
        }
    }
}
