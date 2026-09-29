package dev.horizon.platform.common.id;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Generator of UUID version 7 (RFC 9562) — time-ordered identifiers.
 *
 * <p>Rationale: primary keys are inserted into B-tree indexes constantly. Random UUIDv4 keys cause
 * page splits and index bloat; UUIDv7 is monotonic by embedded millisecond timestamp, so inserts
 * stay on the right-hand edge of the index while remaining globally unique and non-guessable.
 *
 * <p>Layout: 48 bits Unix epoch millis | 4 bits version | 12 bits sub-millisecond counter | 2 bits
 * variant | 62 bits randomness.
 *
 * <p>Monotonicity within the same millisecond is guaranteed by a counter; when the counter would
 * overflow, generation waits for the next millisecond by advancing the internal timestamp.
 * Thread-safe.
 */
public final class Uuid7 {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int MAX_COUNTER = 0x0FFF;

    /** Packs the last used timestamp (high bits) and counter (low 12 bits) into one atomic value. */
    private final AtomicLong state = new AtomicLong(0L);

    private final Clock clock;

    public Uuid7(Clock clock) {
        this.clock = clock;
    }

    private static final Uuid7 SYSTEM = new Uuid7(Clock.systemUTC());

    /** Generates a UUIDv7 using the system UTC clock. */
    public static UUID randomUuid7() {
        return SYSTEM.next();
    }

    /** Generates the next UUIDv7 from this generator's clock. */
    public UUID next() {
        long millis = clock.millis();
        long packed = state.updateAndGet(previous -> {
            long previousMillis = previous >>> 12;
            long previousCounter = previous & MAX_COUNTER;
            if (millis > previousMillis) {
                return (millis << 12);
            }
            if (previousCounter < MAX_COUNTER) {
                return (previousMillis << 12) | (previousCounter + 1);
            }
            // Counter exhausted within the same millisecond: borrow from the next millisecond.
            return ((previousMillis + 1) << 12);
        });

        long timestamp = packed >>> 12;
        long counter = packed & MAX_COUNTER;

        long most = (timestamp << 16) | (0x7L << 12) | counter;
        long least = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(most, least);
    }

    /** Extracts the embedded creation instant. Useful for diagnostics and partition routing. */
    public static long timestampMillis(UUID uuid) {
        if (uuid.version() != 7) {
            throw new IllegalArgumentException("Not a UUIDv7: " + uuid);
        }
        return uuid.getMostSignificantBits() >>> 16;
    }
}
