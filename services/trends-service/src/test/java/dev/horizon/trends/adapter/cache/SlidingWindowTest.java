package dev.horizon.trends.adapter.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * Bucketing of the quota window — untested until a refund had to land in the right bucket.
 *
 * <p>The counter is charged in one bucket and, when a request fails, given back in another unless
 * the moment of the charge is carried along. On an hour boundary that credits a bucket nobody paid
 * into and leaves the paid one standing, and it happens silently: the floor that protects against
 * credit-without-charge also hides the mistake.
 */
class SlidingWindowTest {

    private static final SlidingWindow HOUR = new SlidingWindow(Duration.ofHours(1));

    @Test
    void twoMomentsInsideOneHourShareABucket() {
        long early = HOUR.bucket(Instant.parse("2026-03-01T10:00:01Z"));
        long late = HOUR.bucket(Instant.parse("2026-03-01T10:59:59Z"));

        assertThat(early).isEqualTo(late);
    }

    @Test
    void aSecondApartAcrossTheBoundaryFallsIntoDifferentBuckets() {
        // This is the whole reason `refund` takes the instant of the charge: a slow failure that
        // straddles this line would otherwise compensate the wrong hour.
        long before = HOUR.bucket(Instant.parse("2026-03-01T10:59:59Z"));
        long after = HOUR.bucket(Instant.parse("2026-03-01T11:00:00Z"));

        assertThat(after).isEqualTo(before + 1);
    }

    @Test
    void bucketsAdvanceByOnePerWindow() {
        long first = HOUR.bucket(Instant.parse("2026-03-01T00:00:00Z"));
        long fifth = HOUR.bucket(Instant.parse("2026-03-01T04:00:00Z"));

        assertThat(fifth - first).isEqualTo(4);
    }

    @Test
    void theBucketIsStableForTheSameInstant() {
        var at = Instant.parse("2026-03-01T10:30:00Z");

        assertThat(HOUR.bucket(at)).isEqualTo(HOUR.bucket(at));
    }

    @Test
    void elapsedFractionRunsFromZeroTowardsOneWithoutReachingIt() {
        assertThat(HOUR.elapsedFraction(Instant.parse("2026-03-01T10:00:00Z"))).isZero();
        assertThat(HOUR.elapsedFraction(Instant.parse("2026-03-01T10:30:00Z"))).isEqualTo(0.5);
        assertThat(HOUR.elapsedFraction(Instant.parse("2026-03-01T10:59:59Z"))).isLessThan(1.0);
    }

    @Test
    void bucketingHoldsBeforeTheEpochToo() {
        // floorDiv, not integer division: the latter rounds towards zero and would put two distinct
        // hours before 1970 into the same bucket.
        long earlier = HOUR.bucket(Instant.parse("1969-12-31T22:30:00Z"));
        long later = HOUR.bucket(Instant.parse("1969-12-31T23:30:00Z"));

        assertThat(later).isEqualTo(earlier + 1);
    }
}
