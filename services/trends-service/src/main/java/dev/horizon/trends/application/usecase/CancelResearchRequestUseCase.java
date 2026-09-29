package dev.horizon.trends.application.usecase;

import java.time.Clock;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.horizon.platform.common.event.DomainEventPublisher;
import dev.horizon.trends.application.port.ProgressBroadcaster;
import dev.horizon.trends.application.port.ResearchMetrics;
import dev.horizon.trends.application.port.ResearchRequestRepository;
import dev.horizon.trends.domain.research.ReportViewer;
import dev.horizon.trends.domain.research.ResearchRequest;
import dev.horizon.trends.domain.research.ResearchRequestId;

/**
 * Cancels a running analysis (UC-02 alternative flow).
 *
 * <p>Cancellation is cooperative: downstream steps are not aborted mid-flight, but their results are
 * ignored because the aggregate is already terminal and the saga skips terminal requests. This keeps
 * the design simple and avoids distributed cancellation, at the cost of some wasted compute — an
 * acceptable trade for a 90-second pipeline.
 */
@Service
public class CancelResearchRequestUseCase {

    private final ResearchRequestRepository requests;
    private final DomainEventPublisher events;
    private final ProgressBroadcaster progress;
    private final ResearchMetrics metrics;
    private final Clock clock;

    public CancelResearchRequestUseCase(
            ResearchRequestRepository requests,
            DomainEventPublisher events,
            ProgressBroadcaster progress,
            ResearchMetrics metrics,
            Clock clock) {
        this.requests = requests;
        this.events = events;
        this.progress = progress;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Transactional
    public ResearchRequest cancel(ResearchRequestId id, ReportViewer viewer) {
        var request = requests.findByIdForUpdate(id)
                .orElseThrow(() -> dev.horizon.platform.common.error.HorizonException.notFound("Запрос", id));
        // Ownership, not visibility: cancelling is a change, and the quota it wasted was charged to
        // one person. Refused as "not found", like every other access refusal here, so the answer
        // cannot be used to tell an existing request from a missing one.
        if (!request.isOwnedBy(viewer)) {
            throw dev.horizon.platform.common.error.HorizonException.notFound("Запрос", id);
        }
        request.cancel(clock.instant());
        metrics.requestCancelled();
        var saved = requests.save(request);
        events.publish(saved.drainEvents());
        progress.broadcast(saved);
        return saved;
    }
}
