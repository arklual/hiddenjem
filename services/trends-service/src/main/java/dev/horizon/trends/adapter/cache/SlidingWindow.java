package dev.horizon.trends.adapter.cache;

import java.time.Duration;
import java.time.Instant;

/**
 * Sliding-window-counter arithmetic, extracted so it can be tested without a broker.
 *
 * <p>Why not a plain fixed window: with hourly buckets a user can spend the whole hourly budget at
 * 10:59 and the whole next one at 11:01 — twice the intended rate in two minutes. Why not an exact
 * sliding log: it needs one stored entry per request and an O(n) trim on every check.
 *
 * <p>The counter approximates the true sliding count by weighting the previous bucket by the share
 * of it that still falls inside the window:
 *
 * <pre>estimate = previous × (1 − elapsedFraction) + current</pre>
 *
 * <p>Two integers per subject, O(1) per check, and an error small enough that it never matters for a
 * quota measured in tens of requests per hour.
 */
public record SlidingWindow(Duration size) {

    /** Bucket the instant falls into. Used as the key suffix so buckets expire on their own. */
    public long bucket(Instant at) {
        return Math.floorDiv(at.toEpochMilli(), size.toMillis());
    }

    /** How far into the current bucket we are, in [0, 1). */
    public double elapsedFraction(Instant at) {
        long windowMillis = size.toMillis();
        long offset = Math.floorMod(at.toEpochMilli(), windowMillis);
        return (double) offset / windowMillis;
    }

    /** Weighted estimate of the number of events in the trailing window. */
    public double estimate(long previousBucketCount, long currentBucketCount, Instant at) {
        return previousBucketCount * (1.0 - elapsedFraction(at)) + currentBucketCount;
    }

    /**
     * How long a bucket must survive: its own duration plus one more window, because it is still
     * consulted as "the previous bucket" for the whole of the following one.
     */
    public Duration retention() {
        return size.multipliedBy(2);
    }
}
