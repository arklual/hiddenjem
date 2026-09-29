package dev.horizon.trends.adapter.persistence;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import dev.horizon.trends.application.port.DirectionVisitRepository;

/** Implements {@link DirectionVisitRepository} on top of JPA. */
@Repository
public class DirectionVisitRepositoryAdapter implements DirectionVisitRepository {

    private final DirectionVisitJpaRepository jpa;

    public DirectionVisitRepositoryAdapter(DirectionVisitJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Instant> lastSeenBy(UUID userId) {
        Map<UUID, Instant> marks = new HashMap<>();
        for (var visit : jpa.findByIdUserId(userId)) {
            marks.put(visit.getId().getSavedDomainId(), visit.getSeenAt());
        }
        return marks;
    }

    /**
     * Один оператор, а не «поискать и решить».
     *
     * <p>Прочитать и следом вставить значило бы гонку на первом визите: два одновременных запроса —
     * двойной клик, две вкладки, повтор после таймаута — оба увидели бы пустоту, и второй упал бы на
     * нарушении первичного ключа. Действие по смыслу идемпотентно, падать ему не на чем.
     *
     * <p>{@code ON CONFLICT} ещё и дешевле: {@code save()} с назначенным идентификатором — это
     * {@code merge}, то есть свой SELECT перед вставкой вдобавок к уже сделанному.
     */
    @Override
    @Transactional
    public void markSeen(UUID userId, UUID savedDomainId, Instant seenAt) {
        jpa.upsert(userId, savedDomainId, seenAt);
    }
}
