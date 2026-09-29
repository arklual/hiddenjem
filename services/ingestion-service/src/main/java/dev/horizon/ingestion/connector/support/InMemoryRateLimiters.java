package dev.horizon.ingestion.connector.support;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.horizon.ingestion.domain.port.RateLimiter;
import dev.horizon.ingestion.domain.port.RateLimiters;

/**
 * Per-source limiter registry held in process memory.
 *
 * <p>Per replica, not per cluster: a shared limiter would need a round trip to Redis before every
 * outbound request. The configured budget is therefore divided by the replica count in deployment
 * (see the Helm values), which is the usual, honest trade-off — a distributed rate limiter costs
 * more latency than the politeness margin it buys.
 *
 * <p>{@link #reconfigure} replaces the bucket outright rather than mutating it, so a lowered limit
 * takes effect at once instead of after the old bucket drains.
 */
public class InMemoryRateLimiters implements RateLimiters {

    private static final Logger log = LoggerFactory.getLogger(InMemoryRateLimiters.class);

    private final Map<String, TokenBucketRateLimiter> limiters = new ConcurrentHashMap<>();
    private final Map<String, Integer> configured = new ConcurrentHashMap<>();
    private final int burst;

    public InMemoryRateLimiters(int burst) {
        this.burst = Math.max(burst, 1);
    }

    @Override
    public RateLimiter forSource(String sourceId, int permitsPerMinute) {
        Integer current = configured.get(sourceId);
        if (current != null && current != permitsPerMinute) {
            reconfigure(sourceId, permitsPerMinute);
        }
        configured.putIfAbsent(sourceId, permitsPerMinute);
        return limiters.computeIfAbsent(sourceId, id -> TokenBucketRateLimiter.of(permitsPerMinute, burst));
    }

    @Override
    public void reconfigure(String sourceId, int permitsPerMinute) {
        configured.put(sourceId, permitsPerMinute);
        limiters.put(sourceId, TokenBucketRateLimiter.of(permitsPerMinute, burst));
        log.info("Rate limit for {} set to {} requests/minute", sourceId, permitsPerMinute);
    }
}
