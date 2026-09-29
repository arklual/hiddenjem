package dev.horizon.platform.spring.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OutboxMessageTest {

    private static final Instant NOW = Instant.parse("2026-08-05T10:00:00Z");
    private static final Duration BASE = Duration.ofSeconds(1);
    private static final Duration MAX = Duration.ofMinutes(5);

    private OutboxMessage message() {
        return new OutboxMessage(
                UUID.randomUUID(),
                "ResearchRequest",
                "req-1",
                "horizon.trends.ResearchRequestSubmitted",
                "horizon.trends.events.v1",
                "req-1",
                "{}",
                "{}",
                NOW);
    }

    @Test
    @DisplayName("a new message is immediately eligible for delivery")
    void newMessageIsDueImmediately() {
        var message = message();

        assertThat(message.getPublishedAt()).isNull();
        assertThat(message.getAttempts()).isZero();
        assertThat(message.getNextAttemptAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("backoff doubles per attempt")
    void backoffGrowsExponentially() {
        var message = message();

        message.markFailed(NOW, "boom", BASE, MAX);
        assertThat(message.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(1));

        message.markFailed(NOW, "boom", BASE, MAX);
        assertThat(message.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(2));

        message.markFailed(NOW, "boom", BASE, MAX);
        assertThat(message.getNextAttemptAt()).isEqualTo(NOW.plusSeconds(4));
    }

    @Test
    @DisplayName("backoff is capped so the queue still drains after a long outage")
    void backoffIsCapped() {
        // Without a cap, 20 failed attempts would schedule the next try ~12 days out and the
        // message would effectively be lost even though the broker had recovered.
        var message = message();

        for (int i = 0; i < 30; i++) {
            message.markFailed(NOW, "boom", BASE, MAX);
        }

        assertThat(message.getNextAttemptAt()).isEqualTo(NOW.plus(MAX));
        assertThat(message.getAttempts()).isEqualTo((short) 30);
    }

    @Test
    @DisplayName("a very long error message is truncated instead of blowing up the column")
    void truncatesLongErrors() {
        var message = message();

        message.markFailed(NOW, "x".repeat(5_000), BASE, MAX);

        assertThat(message.getLastError()).hasSize(2_000);
    }

    @Test
    @DisplayName("publishing clears the previous error so a recovered message reads cleanly")
    void publishingClearsError() {
        var message = message();
        message.markFailed(NOW, "transient", BASE, MAX);

        message.markPublished(NOW.plusSeconds(10));

        assertThat(message.getPublishedAt()).isEqualTo(NOW.plusSeconds(10));
        assertThat(message.getLastError()).isNull();
    }
}
