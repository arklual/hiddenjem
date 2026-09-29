package dev.horizon.trends.adapter.persistence;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/**
 * JPA mapping of {@code direction_visits} — when this analyst last looked at this direction.
 *
 * <p>Только для чтения: отметка ставится одним {@code ON CONFLICT} в {@link
 * DirectionVisitJpaRepository#upsert}, потому что «поискать и решить» здесь означало бы гонку на
 * первом визите. Конструктора и сеттера нет намеренно — они были бы вторым способом записи, который
 * никто не вызывает.
 */
@Entity
@Table(name = "direction_visits")
public class DirectionVisitEntity {

    /** Composite key: the mark belongs to a person and a direction, and to neither alone. */
    @Embeddable
    public static class Key implements Serializable {

        @Column(name = "user_id", nullable = false)
        private UUID userId;

        @Column(name = "saved_domain_id", nullable = false)
        private UUID savedDomainId;

        protected Key() {}

        public Key(UUID userId, UUID savedDomainId) {
            this.userId = userId;
            this.savedDomainId = savedDomainId;
        }

        public UUID getUserId() {
            return userId;
        }

        public UUID getSavedDomainId() {
            return savedDomainId;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Key key)) {
                return false;
            }
            return Objects.equals(userId, key.userId) && Objects.equals(savedDomainId, key.savedDomainId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, savedDomainId);
        }
    }

    @EmbeddedId
    private Key id;

    @Column(name = "seen_at", nullable = false)
    private Instant seenAt;

    protected DirectionVisitEntity() {}

    public Key getId() {
        return id;
    }

    public Instant getSeenAt() {
        return seenAt;
    }
}
