package dev.horizon.trends.adapter.persistence;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;

import dev.horizon.platform.common.error.HorizonException;
import dev.horizon.platform.common.error.ProblemType;
import dev.horizon.trends.application.port.MethodologyProfileRepository;
import dev.horizon.trends.domain.methodology.MethodologyProfile;

/** Implements {@link MethodologyProfileRepository} on top of JPA + a JSON codec for the weight maps. */
@Repository
public class MethodologyProfileRepositoryAdapter implements MethodologyProfileRepository {

    private static final TypeReference<Map<String, Double>> WEIGHTS = new TypeReference<>() {};
    private static final TypeReference<Map<String, Object>> PARAMETERS = new TypeReference<>() {};

    private final MethodologyProfileJpaRepository jpa;
    private final JsonCodec json;

    public MethodologyProfileRepositoryAdapter(MethodologyProfileJpaRepository jpa, JsonCodec json) {
        this.jpa = jpa;
        this.json = json;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MethodologyProfile> findById(UUID id) {
        if (id == null) {
            return Optional.empty();
        }
        return jpa.findById(id).map(this::toDomain);
    }

    /**
     * @throws HorizonException with {@code internal-error} when no default profile exists — that is a
     *     broken deployment (the V2 migration seeds one), not a user-visible condition, and failing
     *     loudly beats silently analysing with arbitrary weights.
     */
    @Override
    @Transactional(readOnly = true)
    public MethodologyProfile requireDefault() {
        return jpa.findFirstByIsDefaultTrue()
                .map(this::toDomain)
                .orElseThrow(() -> new HorizonException(
                        ProblemType.INTERNAL_ERROR, "Не настроен профиль методологии по умолчанию"));
    }

    private MethodologyProfile toDomain(MethodologyProfileEntity entity) {
        return new MethodologyProfile(
                entity.getId(),
                entity.getName(),
                entity.getVersion(),
                entity.getMethodologyVersion(),
                MethodologyProfile.ScoreAggregator.valueOf(entity.getAggregator()),
                json.read(entity.getWeights(), WEIGHTS),
                json.read(entity.getParameters(), PARAMETERS),
                entity.getConfidenceThreshold(),
                entity.isDefault(),
                entity.getCreatedBy(),
                entity.getCreatedAt());
    }
}
