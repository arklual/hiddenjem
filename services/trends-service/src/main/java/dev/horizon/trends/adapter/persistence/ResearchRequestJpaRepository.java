package dev.horizon.trends.adapter.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Spring Data repository behind {@link ResearchRequestRepositoryAdapter}. */
public interface ResearchRequestJpaRepository extends JpaRepository<ResearchRequestEntity, UUID> {

    /**
     * Loads the row with {@code SELECT … FOR UPDATE}.
     *
     * <p>Saga steps for one request arrive concurrently and each is a read-modify-write of the same
     * row. Without the lock, a late progress event and a completion event can both read
     * {@code ANALYZING} and the second write wins — losing a transition. The lock makes the
     * check-and-set atomic; the cost is bounded because the critical section is a single short
     * transaction per request.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM ResearchRequestEntity r WHERE r.id = :id")
    Optional<ResearchRequestEntity> findByIdForUpdate(@Param("id") UUID id);

    Optional<ResearchRequestEntity> findByUserIdAndIdempotencyKey(UUID userId, String idempotencyKey);

    Page<ResearchRequestEntity> findByUserIdOrderBySubmittedAtDesc(UUID userId, Pageable pageable);

    /**
     * The organisation's history (BR-A52).
     *
     * <p>Reports, reuse and running analyses are already scoped to the organisation; the history was
     * the last place pretending the work is private — and the place where a request joined from a
     * colleague disappeared the moment the tab closed.
     */
    Page<ResearchRequestEntity> findByOrganizationIdOrderBySubmittedAtDesc(UUID organizationId, Pageable pageable);

    Page<ResearchRequestEntity> findByOrganizationIdAndStatusOrderBySubmittedAtDesc(
            UUID organizationId, String status, Pageable pageable);

    Page<ResearchRequestEntity> findByUserIdAndStatusOrderBySubmittedAtDesc(
            UUID userId, String status, Pageable pageable);

    List<ResearchRequestEntity> findByStatusInAndDeadlineAtLessThanOrderByDeadlineAtAsc(
            Collection<String> statuses, Instant deadline, Pageable pageable);

    /**
     * The freshness lookup of BR-A8.
     *
     * <p>Matching on {@code paramsDiscriminator} rather than on the individual parameter columns is
     * what makes "the same question" a single indexable equality predicate — including the
     * order-independent source-class set, which no column comparison could express.
     *
     * <p>Scoped to the organisation, because the cache has to agree with visibility (BR-A43). Without
     * that it answered "the report already exists, here it is" with an identifier the caller would
     * then be refused — and across organisations at that. Handing out something you will not show is
     * a defect, not an optimisation.
     */
    @Query(
            """
            SELECT r FROM ResearchRequestEntity r
            WHERE r.normalizedQuery = :normalizedQuery
              AND r.paramsDiscriminator = :discriminator
              AND r.organizationId = :organizationId
              AND r.status = 'COMPLETED'
              AND r.reportId IS NOT NULL
              AND r.finishedAt >= :notOlderThan
            ORDER BY r.finishedAt DESC
            """)
    List<ResearchRequestEntity> findFreshCompleted(
            @Param("normalizedQuery") String normalizedQuery,
            @Param("discriminator") String discriminator,
            @Param("organizationId") UUID organizationId,
            @Param("notOlderThan") Instant notOlderThan,
            Pageable pageable);

    /**
     * The in-flight lookup of BR-A24.
     *
     * <p>Same pair of predicates as the freshness lookup, but the opposite end of the lifecycle, so
     * it needs its own index — {@code ix_requests_cache_lookup} is partial on {@code COMPLETED}.
     * {@code ix_requests_active_question} of V3 is the one that serves it.
     *
     * <p>The live statuses are bound rather than written out: spelling them here would be the fifth
     * hand-kept copy of that set, and the day a new terminal status appears the forgotten copy would
     * keep reporting finished requests as still running — silently, forever.
     *
     * <p>Ordered oldest first, then by id: when a race has produced two, the earlier one is closer
     * to finishing, and the tiebreak keeps the answer the same on repeated calls.
     *
     * <p>Scoped to the organisation, not the user (BR-A47). A colleague who asked the same question
     * a minute earlier gets their finished report reused; a colleague who asked it at the same
     * moment used to pay for a second collection of the same corpus. The difference between the two
     * was seconds — and the same organisation pays either way.
     */
    @Query(
            """
            SELECT r FROM ResearchRequestEntity r
            WHERE r.organizationId = :organizationId
              AND r.normalizedQuery = :normalizedQuery
              AND r.paramsDiscriminator = :discriminator
              AND r.status IN :liveStatuses
            ORDER BY r.submittedAt ASC, r.id ASC
            """)
    List<ResearchRequestEntity> findActive(
            @Param("organizationId") UUID organizationId,
            @Param("normalizedQuery") String normalizedQuery,
            @Param("discriminator") String discriminator,
            @Param("liveStatuses") Collection<String> liveStatuses,
            Pageable pageable);

    long countByUserIdAndSubmittedAtGreaterThanEqual(UUID userId, Instant since);

    long countByOrganizationIdAndSubmittedAtGreaterThanEqual(UUID organizationId, Instant since);
}
