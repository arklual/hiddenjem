package dev.horizon.trends.adapter.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DirectionVisitJpaRepository extends JpaRepository<DirectionVisitEntity, DirectionVisitEntity.Key> {

    /** All of one analyst's marks: the radar reads them whole, once per screen. */
    List<DirectionVisitEntity> findByIdUserId(UUID userId);

    /** Отметка визита без гонки на первом: см. {@link DirectionVisitRepositoryAdapter#markSeen}. */
    @Modifying
    @Query(
            value =
                    """
                    INSERT INTO direction_visits (user_id, saved_domain_id, seen_at)
                    VALUES (:userId, :savedDomainId, :seenAt)
                    ON CONFLICT (user_id, saved_domain_id) DO UPDATE SET seen_at = excluded.seen_at
                    """,
            nativeQuery = true)
    void upsert(
            @Param("userId") UUID userId, @Param("savedDomainId") UUID savedDomainId, @Param("seenAt") Instant seenAt);
}
