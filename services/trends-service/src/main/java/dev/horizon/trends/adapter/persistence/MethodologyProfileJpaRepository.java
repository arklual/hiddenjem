package dev.horizon.trends.adapter.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MethodologyProfileJpaRepository extends JpaRepository<MethodologyProfileEntity, UUID> {

    Optional<MethodologyProfileEntity> findFirstByIsDefaultTrue();
}
