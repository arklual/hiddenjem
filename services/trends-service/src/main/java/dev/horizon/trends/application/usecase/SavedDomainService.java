package dev.horizon.trends.application.usecase;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.horizon.platform.common.error.HorizonException;
import dev.horizon.trends.application.port.MethodologyProfileRepository;
import dev.horizon.trends.application.port.SavedDomainRepository;
import dev.horizon.trends.domain.research.AnalysisParameters;
import dev.horizon.trends.domain.research.TechnologyDomainQuery;
import dev.horizon.trends.domain.saveddomain.SavedDomain;

/** Saved directions an analyst tracks over time (UC-08). */
@Service
public class SavedDomainService {

    private final SavedDomainRepository repository;
    private final MethodologyProfileRepository profiles;
    private final Clock clock;

    public SavedDomainService(SavedDomainRepository repository, MethodologyProfileRepository profiles, Clock clock) {
        this.repository = repository;
        this.profiles = profiles;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<SavedDomain> list(UUID userId) {
        return repository.findByUser(userId);
    }

    @Transactional
    public SavedDomain save(UUID userId, String rawQuery, AnalysisParameters parameters) {
        var query = TechnologyDomainQuery.of(rawQuery);
        repository.findByUserAndQuery(userId, query.normalized()).ifPresent(existing -> {
            throw HorizonException.conflict("Это направление уже сохранено");
        });
        if (repository.countByUser(userId) >= SavedDomain.MAX_PER_USER) {
            throw HorizonException.conflict(
                    "Достигнут предел сохранённых направлений (%d)".formatted(SavedDomain.MAX_PER_USER));
        }
        // Профиль всегда по умолчанию: выбора профиля в продукте больше нет.
        var resolved = parameters.withProfile(profiles.requireDefault().id());
        return repository.save(SavedDomain.create(userId, query, resolved, clock.instant()));
    }

    @Transactional
    public void delete(UUID userId, UUID id) {
        if (!repository.delete(userId, id)) {
            throw HorizonException.notFound("Сохранённое направление", id);
        }
    }
}
