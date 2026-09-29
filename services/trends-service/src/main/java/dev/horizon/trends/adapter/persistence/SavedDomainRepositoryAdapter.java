package dev.horizon.trends.adapter.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import dev.horizon.trends.application.port.SavedDomainRepository;
import dev.horizon.trends.domain.report.TrendReportId;
import dev.horizon.trends.domain.research.TechnologyDomainQuery;
import dev.horizon.trends.domain.saveddomain.SavedDomain;

/** Implements {@link SavedDomainRepository} on top of JPA. */
@Repository
public class SavedDomainRepositoryAdapter implements SavedDomainRepository {

    private final SavedDomainJpaRepository jpa;
    private final JsonCodec json;

    public SavedDomainRepositoryAdapter(SavedDomainJpaRepository jpa, JsonCodec json) {
        this.jpa = jpa;
        this.json = json;
    }

    @Override
    @Transactional(readOnly = true)
    public List<SavedDomain> findByUser(UUID userId) {
        return jpa.findByUserIdOrderByCreatedAtDescIdAsc(userId).stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SavedDomain> findByUserAndQuery(UUID userId, String normalizedQuery) {
        return jpa.findByUserIdAndNormalizedQuery(userId, normalizedQuery).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public long countByUser(UUID userId) {
        return jpa.countByUserId(userId);
    }

    @Override
    public SavedDomain save(SavedDomain domain) {
        var existing = jpa.findById(domain.id()).orElse(null);
        if (existing != null) {
            existing.setLastReportId(
                    domain.lastReport().map(TrendReportId::value).orElse(null));
            return domain;
        }
        jpa.save(new SavedDomainEntity(
                domain.id(),
                domain.userId(),
                domain.query().raw(),
                domain.query().normalized(),
                domain.query().language(),
                json.write(StoredParameters.from(domain.parameters())),
                domain.lastReport().map(TrendReportId::value).orElse(null),
                domain.createdAt()));
        return domain;
    }

    @Override
    public boolean delete(UUID userId, UUID id) {
        return jpa.deleteByUserIdAndId(userId, id) > 0;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsForUser(UUID userId, UUID id) {
        return jpa.existsByIdAndUserId(id, userId);
    }

    private SavedDomain toDomain(SavedDomainEntity entity) {
        return new SavedDomain(
                entity.getId(),
                entity.getUserId(),
                new TechnologyDomainQuery(entity.getRawQuery(), entity.getNormalizedQuery(), entity.getQueryLanguage()),
                json.read(entity.getParameters(), StoredParameters.class).toDomain(),
                entity.getLastReportId() == null ? null : new TrendReportId(entity.getLastReportId()),
                entity.getCreatedAt());
    }
}
