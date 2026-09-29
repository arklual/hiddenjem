package dev.horizon.trends.domain.research;

import java.util.UUID;

import dev.horizon.platform.common.util.Guards;

/**
 * Reference to the user who asked for the analysis.
 *
 * <p>A reference by identifier, not an object: {@code User} is an aggregate owned by another
 * bounded context, and holding it here would couple the two models (Vernon, "reference other
 * aggregates by identity").
 */
public record RequesterRef(UUID userId, UUID organizationId) {
    public RequesterRef {
        Guards.requireNonNull(userId, "userId");
        Guards.requireNonNull(organizationId, "organizationId");
    }
}
