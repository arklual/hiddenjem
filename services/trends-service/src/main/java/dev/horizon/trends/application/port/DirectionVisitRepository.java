package dev.horizon.trends.application.port;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * When each direction was last seen by one analyst (BR-A62, BR-A63).
 *
 * <p>Per person, not per direction: a direction belongs to the organisation, but "I have seen this"
 * does not — a colleague's visit must not extinguish my news.
 */
public interface DirectionVisitRepository {

    /** @return direction id → the moment this analyst last opened it; absent means never */
    Map<UUID, Instant> lastSeenBy(UUID userId);

    void markSeen(UUID userId, UUID savedDomainId, Instant seenAt);
}
