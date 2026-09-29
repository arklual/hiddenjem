package dev.horizon.ingestion.domain.port;

/**
 * Politeness budget for one source (FR-04.4, NFR-S12, BR-C3).
 *
 * <p>Respecting a source's published rate limit is a compliance requirement, not an optimisation:
 * exceeding it gets the platform's address blocked and breaks every future collection.
 */
public interface RateLimiter {

    /** Blocks until a permit is available. */
    void acquire();

    /** Takes a permit if one is immediately available. */
    boolean tryAcquire();

    /** Permits granted per minute. */
    int permitsPerMinute();
}
