package dev.horizon.trends.application.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import dev.horizon.trends.domain.saveddomain.SavedDomain;

public interface SavedDomainRepository {

    List<SavedDomain> findByUser(UUID userId);

    Optional<SavedDomain> findByUserAndQuery(UUID userId, String normalizedQuery);

    long countByUser(UUID userId);

    /**
     * Принадлежит ли направление этому аналитику.
     *
     * <p>Отдельный метод, а не фильтр по {@link #findByUser}: чтобы ответить «да/нет», незачем
     * поднимать весь портфель и разбирать jsonb каждой строки.
     */
    boolean existsForUser(UUID userId, UUID id);

    SavedDomain save(SavedDomain domain);

    boolean delete(UUID userId, UUID id);
}
