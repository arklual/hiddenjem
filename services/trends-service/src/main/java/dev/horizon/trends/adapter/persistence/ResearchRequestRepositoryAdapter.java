package dev.horizon.trends.adapter.persistence;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import dev.horizon.trends.application.port.PageResult;
import dev.horizon.trends.application.port.ResearchRequestRepository;
import dev.horizon.trends.domain.research.AnalysisParameters;
import dev.horizon.trends.domain.research.ReportViewer;
import dev.horizon.trends.domain.research.ResearchRequest;
import dev.horizon.trends.domain.research.ResearchRequestId;
import dev.horizon.trends.domain.research.ResearchStatus;

/** Implements {@link ResearchRequestRepository} on top of JPA. */
@Repository
public class ResearchRequestRepositoryAdapter implements ResearchRequestRepository {

    /**
     * The statuses a request can still move on from — mirrors the partial indexes in V1 and V3.
     *
     * <p>Derived from the aggregate's own notion of "terminal" rather than listed again: a list
     * would be a second definition, and the copy that gets forgotten is the one that decides whether
     * a finished request looks like a running one.
     */
    private static final Set<ResearchStatus> ACTIVE = EnumSet.copyOf(EnumSet.allOf(ResearchStatus.class).stream()
            .filter(status -> !status.isTerminal())
            .toList());

    private static final List<String> ACTIVE_NAMES =
            ACTIVE.stream().map(Enum::name).sorted().toList();

    private final ResearchRequestJpaRepository jpa;
    private final ResearchRequestMapper mapper;

    public ResearchRequestRepositoryAdapter(ResearchRequestJpaRepository jpa, ResearchRequestMapper mapper) {
        this.jpa = jpa;
        this.mapper = mapper;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ResearchRequest> findById(ResearchRequestId id) {
        return jpa.findById(id.value()).map(mapper::toDomain);
    }

    @Override
    public Optional<ResearchRequest> findByIdForUpdate(ResearchRequestId id) {
        return jpa.findByIdForUpdate(id.value()).map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ResearchRequest> findByIdempotencyKey(UUID userId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return Optional.empty();
        }
        return jpa.findByUserIdAndIdempotencyKey(userId, idempotencyKey).map(mapper::toDomain);
    }

    /**
     * Persists the aggregate and returns <em>the same instance</em>.
     *
     * <p>Returning a freshly rehydrated copy would be tidier in isolation but would silently drop the
     * aggregate's pending domain events: callers do {@code publish(save(request).drainEvents())}, and
     * a rehydrated aggregate has an empty event list. The recorded facts and the persisted state must
     * leave this method together or the outbox loses events.
     */
    @Override
    public ResearchRequest save(ResearchRequest request) {
        var existing = jpa.findById(request.id().value()).orElse(null);
        var entity = mapper.toEntity(request, existing);
        if (existing == null) {
            jpa.save(entity);
        }
        return request;
    }

    @Override
    @Transactional(readOnly = true)
    public PageResult<ResearchRequest> findHistory(
            ReportViewer viewer, boolean onlyMine, ResearchStatus status, int page, int size) {
        var pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100));
        // Без организации расширять не по чему, и «все» тогда означает «мои» — не потому, что так
        // удобнее, а потому, что других видимых ему запросов не существует (P4 спеки 15).
        boolean personal = onlyMine || viewer.organizationId() == null;
        var result = personal
                ? (status == null
                        ? jpa.findByUserIdOrderBySubmittedAtDesc(viewer.userId(), pageable)
                        : jpa.findByUserIdAndStatusOrderBySubmittedAtDesc(viewer.userId(), status.name(), pageable))
                : (status == null
                        ? jpa.findByOrganizationIdOrderBySubmittedAtDesc(viewer.organizationId(), pageable)
                        : jpa.findByOrganizationIdAndStatusOrderBySubmittedAtDesc(
                                viewer.organizationId(), status.name(), pageable));
        return new PageResult<>(
                result.getContent().stream().map(mapper::toDomain).toList(),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements());
    }

    @Override
    @Transactional(readOnly = true)
    public List<ResearchRequest> findOverdue(Instant now, int limit) {
        return jpa
                .findByStatusInAndDeadlineAtLessThanOrderByDeadlineAtAsc(
                        ACTIVE_NAMES, now, PageRequest.of(0, Math.max(limit, 1)))
                .stream()
                .map(mapper::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ResearchRequest> findActive(
            UUID organizationId, String normalizedQuery, AnalysisParameters parameters) {
        return jpa
                .findActive(
                        organizationId,
                        normalizedQuery,
                        parameters.cacheDiscriminator(),
                        ACTIVE_NAMES,
                        PageRequest.of(0, 1))
                .stream()
                .findFirst()
                .map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ResearchRequest> findFreshCompleted(
            String normalizedQuery, AnalysisParameters parameters, UUID organizationId, Instant notOlderThan) {
        return jpa
                .findFreshCompleted(
                        normalizedQuery,
                        parameters.cacheDiscriminator(),
                        organizationId,
                        notOlderThan,
                        PageRequest.of(0, 1))
                .stream()
                .findFirst()
                .map(mapper::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public long countSubmittedSince(UUID userId, Instant since) {
        return jpa.countByUserIdAndSubmittedAtGreaterThanEqual(userId, since);
    }

    @Override
    @Transactional(readOnly = true)
    public long countSubmittedByOrganizationSince(UUID organizationId, Instant since) {
        return jpa.countByOrganizationIdAndSubmittedAtGreaterThanEqual(organizationId, since);
    }
}
