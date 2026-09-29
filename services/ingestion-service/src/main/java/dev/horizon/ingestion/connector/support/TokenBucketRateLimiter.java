package dev.horizon.ingestion.connector.support;

import java.util.function.LongSupplier;

import dev.horizon.ingestion.domain.port.RateLimiter;
import dev.horizon.platform.common.util.Guards;

/**
 * Token bucket enforcing a source's published request budget (FR-04.4, BR-C3).
 *
 * <p>Token bucket rather than a fixed window because the constraint sources actually publish is a
 * <em>rate</em> ("1 request per 3 seconds" for arXiv), and a fixed window permits a double burst at
 * the boundary. Capacity is the allowed burst; for arXiv it is 1, which turns the limiter into a
 * strict spacer.
 *
 * <p>Time and sleeping are injected. That is what makes the behaviour testable at all: the unit test
 * advances a fake nanosecond clock instead of waiting three real seconds, so the rate limit is
 * verified precisely and the suite stays fast.
 */
public final class TokenBucketRateLimiter implements RateLimiter {

    private static final long NANOS_PER_MINUTE = 60_000_000_000L;

    /** Waits for a number of nanoseconds; the seam that keeps tests instantaneous. */
    @FunctionalInterface
    public interface Sleeper {
        void sleepNanos(long nanos) throws InterruptedException;
    }

    private final int permitsPerMinute;
    private final double capacity;
    private final long nanosPerPermit;
    private final LongSupplier nanoTime;
    private final Sleeper sleeper;

    private double tokens;
    private long lastRefillNanos;

    public TokenBucketRateLimiter(int permitsPerMinute, int burst, LongSupplier nanoTime, Sleeper sleeper) {
        this.permitsPerMinute = Guards.requireRange(permitsPerMinute, "permitsPerMinute", 1, 600_000);
        this.capacity = Math.max(1, burst);
        this.nanosPerPermit = Math.max(1L, NANOS_PER_MINUTE / permitsPerMinute);
        this.nanoTime = Guards.requireNonNull(nanoTime, "nanoTime");
        this.sleeper = Guards.requireNonNull(sleeper, "sleeper");
        this.tokens = this.capacity;
        this.lastRefillNanos = nanoTime.getAsLong();
    }

    /** Production limiter: system nanotime, real sleeping. */
    public static TokenBucketRateLimiter of(int permitsPerMinute, int burst) {
        return new TokenBucketRateLimiter(permitsPerMinute, burst, System::nanoTime, nanos -> {
            if (nanos > 0) {
                Thread.sleep(nanos / 1_000_000L, (int) (nanos % 1_000_000L));
            }
        });
    }

    @Override
    public void acquire() {
        while (true) {
            long waitNanos;
            synchronized (this) {
                refill();
                if (tokens >= 1.0) {
                    tokens -= 1.0;
                    return;
                }
                waitNanos = (long) Math.ceil((1.0 - tokens) * nanosPerPermit);
            }
            try {
                sleeper.sleepNanos(waitNanos);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ConnectorException.Permanent(null, 0, "Interrupted while waiting for a rate-limit permit", e);
            }
        }
    }

    @Override
    public synchronized boolean tryAcquire() {
        refill();
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }

    @Override
    public int permitsPerMinute() {
        return permitsPerMinute;
    }

    /** Tokens currently available — for tests and diagnostics. */
    public synchronized double availablePermits() {
        refill();
        return tokens;
    }

    private void refill() {
        long now = nanoTime.getAsLong();
        long elapsed = now - lastRefillNanos;
        if (elapsed <= 0) {
            return;
        }
        double refilled = (double) elapsed / (double) nanosPerPermit;
        if (refilled <= 0) {
            return;
        }
        tokens = Math.min(capacity, tokens + refilled);
        lastRefillNanos = now;
    }
}
