package dev.horizon.trends.adapter.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SavedDomainJpaRepository extends JpaRepository<SavedDomainEntity, UUID> {

    List<SavedDomainEntity> findByUserIdOrderByCreatedAtDescIdAsc(UUID userId);

    Optional<SavedDomainEntity> findByUserIdAndNormalizedQuery(UUID userId, String normalizedQuery);

    long countByUserId(UUID userId);

    /** Scoped by user: an id alone must never be enough to delete someone else's row (IDOR). */
    long deleteByUserIdAndId(UUID userId, UUID id);

    boolean existsByIdAndUserId(UUID id, UUID userId);
}
