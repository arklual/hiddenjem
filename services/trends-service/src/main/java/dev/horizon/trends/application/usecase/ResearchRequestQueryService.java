package dev.horizon.trends.application.usecase;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.horizon.platform.common.error.HorizonException;
import dev.horizon.trends.application.port.PageResult;
import dev.horizon.trends.application.port.ResearchRequestRepository;
import dev.horizon.trends.domain.research.ReportViewer;
import dev.horizon.trends.domain.research.ResearchRequest;
import dev.horizon.trends.domain.research.ResearchRequestId;
import dev.horizon.trends.domain.research.ResearchStatus;

/** Read side for research requests (UC-03, UC-08). */
@Service
@Transactional(readOnly = true)
public class ResearchRequestQueryService {

    private final ResearchRequestRepository requests;

    public ResearchRequestQueryService(ResearchRequestRepository requests) {
        this.requests = requests;
    }

    /**
     * Authorisation is enforced here rather than in the controller so that every caller — REST, SSE
     * or a future scheduled digest — goes through the same check.
     */
    public ResearchRequest get(ResearchRequestId id, ReportViewer viewer) {
        var request = requests.findById(id).orElseThrow(() -> HorizonException.notFound("Запрос", id));
        if (!request.isVisibleTo(viewer)) {
            // Deliberately 404, not 403: revealing that someone else's request exists is an
            // information leak (IDOR-style enumeration).
            throw HorizonException.notFound("Запрос", id);
        }
        return request;
    }

    public PageResult<ResearchRequest> history(
            ReportViewer viewer, boolean onlyMine, ResearchStatus status, int page, int size) {
        return requests.findHistory(viewer, onlyMine, status, page, Math.min(Math.max(size, 1), 100));
    }
}
