package dev.horizon.platform.common.id;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class Uuid7Test {

    private static final Instant FIXED = Instant.parse("2026-08-05T10:15:30.123Z");

    @Test
    @DisplayName("has version 7 and the RFC 9562 variant bits")
    void hasCorrectVersionAndVariant() {
        var uuid = new Uuid7(Clock.fixed(FIXED, ZoneOffset.UTC)).next();

        assertThat(uuid.version()).isEqualTo(7);
        assertThat(uuid.variant()).isEqualTo(2); // IETF variant: high bits 10
    }

    @Test
    @DisplayName("embeds the generation timestamp so ids sort by creation time")
    void embedsTimestamp() {
        var uuid = new Uuid7(Clock.fixed(FIXED, ZoneOffset.UTC)).next();

        assertThat(Uuid7.timestampMillis(uuid)).isEqualTo(FIXED.toEpochMilli());
    }

    @Test
    @DisplayName("stays strictly increasing inside a single millisecond")
    void monotonicWithinSameMillisecond() {
        // The whole point of UUIDv7 for primary keys is index locality; if ids generated in the
        // same millisecond were unordered, batch inserts would scatter across index pages again.
        var generator = new Uuid7(Clock.fixed(FIXED, ZoneOffset.UTC));

        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            ids.add(generator.next());
        }

        for (int i = 1; i < ids.size(); i++) {
            assertThat(compareUnsigned(ids.get(i - 1), ids.get(i)))
                    .as("id %d must be smaller than id %d", i - 1, i)
                    .isNegative();
        }
    }

    @Test
    @DisplayName("borrows from the next millisecond when the 12-bit counter is exhausted")
    void handlesCounterOverflow() {
        var generator = new Uuid7(Clock.fixed(FIXED, ZoneOffset.UTC));

        // 4096 values fit in the counter; the 4097th must roll into the next millisecond rather
        // than repeat or go backwards.
        UUID previous = generator.next();
        for (int i = 0; i < 5000; i++) {
            UUID current = generator.next();
            assertThat(compareUnsigned(previous, current)).isNegative();
            previous = current;
        }
        assertThat(Uuid7.timestampMillis(previous)).isGreaterThan(FIXED.toEpochMilli());
    }

    @Test
    @DisplayName("produces no collisions under concurrent generation")
    void isThreadSafe() throws Exception {
        var generator = new Uuid7(Clock.fixed(FIXED, ZoneOffset.UTC));
        int threads = 8;
        int perThread = 2_000;
        var latch = new CountDownLatch(1);
        var results = new ArrayList<Set<UUID>>();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<Set<UUID>>>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    latch.await();
                    Set<UUID> local = new HashSet<>();
                    for (int i = 0; i < perThread; i++) {
                        local.add(generator.next());
                    }
                    return local;
                }));
            }
            latch.countDown();
            for (var future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        Set<UUID> all = new HashSet<>();
        results.forEach(all::addAll);
        assertThat(all).hasSize(threads * perThread);
    }

    @Test
    @DisplayName("advances with the clock")
    void advancesWithClock() {
        var start = Clock.fixed(FIXED, ZoneOffset.UTC);
        var later = Clock.offset(start, Duration.ofSeconds(5));

        var early = new Uuid7(start).next();
        var late = new Uuid7(later).next();

        assertThat(compareUnsigned(early, late)).isNegative();
    }

    @Test
    @DisplayName("rejects a non-v7 uuid when extracting the timestamp")
    void rejectsForeignUuid() {
        var v4 = UUID.randomUUID();

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> Uuid7.timestampMillis(v4)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    /** Lexicographic comparison of the 128 bits, which is how a database index orders them. */
    private static int compareUnsigned(UUID a, UUID b) {
        int high = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    }
}
