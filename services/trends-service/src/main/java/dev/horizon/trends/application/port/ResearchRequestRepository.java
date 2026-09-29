package dev.horizon.trends.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import dev.horizon.trends.domain.research.AnalysisParameters;
import dev.horizon.trends.domain.research.ReportViewer;
import dev.horizon.trends.domain.research.ResearchRequest;
import dev.horizon.trends.domain.research.ResearchRequestId;
import dev.horizon.trends.domain.research.ResearchStatus;

/** Outbound port for research-request persistence. Implemented in the persistence adapter. */
public interface ResearchRequestRepository {

    Optional<ResearchRequest> findById(ResearchRequestId id);

    /**
     * Loads for update with a pessimistic lock.
     *
     * <p>Saga steps arrive concurrently (a late progress event may race a completion event); locking
     * the row makes the transition check-and-set atomic instead of last-writer-wins.
     */
    Optional<ResearchRequest> findByIdForUpdate(ResearchRequestId id);

    Optional<ResearchRequest> findByIdempotencyKey(UUID userId, String idempotencyKey);

    ResearchRequest save(ResearchRequest request);

    /**
     * @param viewer whose history to show; the organisation comes from the same value the access rule
     *     uses, so widening the list cannot outrun widening the right to read it
     * @param onlyMine narrow back to the caller's own requests (BR-A54)
     */
    PageResult<ResearchRequest> findHistory(
            ReportViewer viewer, boolean onlyMine, ResearchStatus status, int page, int size);

    /** Active requests whose deadline has passed — input for the timeout sweeper (FR-02.5). */
    List<ResearchRequest> findOverdue(Instant now, int limit);

    /**
     * An unfinished request of the same organisation for the same question (BR-A47).
     *
     * <p>Scoped to the organisation, not the user. The corpus is collected once and the bill arrives
     * once, so a colleague who pressed the same button a second earlier is not asking a different
     * question — they are asking the same one, already being answered. The returned request is
     * readable by the caller for the same reason it is returned: one organisation (BR-A42).
     */
    Optional<ResearchRequest> findActive(UUID organizationId, String normalizedQuery, AnalysisParameters parameters);

    /** Most recent completed request for the same question, used by the caching policy (BR-A8). */
    /**
     * @param organizationId whose cache to look in. Scoped, because a reused result has to be one the
     *     caller can actually open: an unscoped lookup named a report and then refused it.
     */
    Optional<ResearchRequest> findFreshCompleted(
            String normalizedQuery, AnalysisParameters parameters, UUID organizationId, Instant notOlderThan);

    long countSubmittedSince(UUID userId, Instant since);

    long countSubmittedByOrganizationSince(UUID organizationId, Instant since);
}
