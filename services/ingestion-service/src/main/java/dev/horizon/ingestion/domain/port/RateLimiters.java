package dev.horizon.ingestion.domain.port;

/**
 * Registry of per-source {@link RateLimiter}s.
 *
 * <p>A port rather than a static holder because the limit is editable at runtime
 * ({@code PATCH /api/v1/sources/{id}}): the use case that changes it needs a way to say so without
 * reaching into a connector adapter.
 */
public interface RateLimiters {

    /** Returns the limiter for a source, creating it with {@code permitsPerMinute} if absent. */
    RateLimiter forSource(String sourceId, int permitsPerMinute);

    /** Applies a new budget; in-flight waiters are released under the new rate. */
    void reconfigure(String sourceId, int permitsPerMinute);
}
