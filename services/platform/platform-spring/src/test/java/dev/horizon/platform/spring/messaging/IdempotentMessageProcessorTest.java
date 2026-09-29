package dev.horizon.platform.spring.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class IdempotentMessageProcessorTest {

    private static final Instant NOW = Instant.parse("2026-08-05T10:00:00Z");

    @Mock
    private ProcessedMessageRepository repository;

    private IdempotentMessageProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new IdempotentMessageProcessor(repository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("runs the handler once for an unseen message")
    void runsHandlerForNewMessage() {
        when(repository.claim(eq("saga"), eq("m-1"), any())).thenReturn(1);
        var invocations = new AtomicInteger();

        boolean handled = processor.processOnce("saga", "m-1", "payload", value -> invocations.incrementAndGet());

        assertThat(handled).isTrue();
        assertThat(invocations).hasValue(1);
    }

    @Test
    @DisplayName("skips a redelivered message — at-least-once delivery makes this the normal case")
    void skipsDuplicate() {
        when(repository.claim(eq("saga"), eq("m-1"), any())).thenReturn(0);
        var invocations = new AtomicInteger();

        boolean handled = processor.processOnce("saga", "m-1", "payload", value -> invocations.incrementAndGet());

        assertThat(handled).isFalse();
        assertThat(invocations).hasValue(0);
    }

    @Test
    @DisplayName("loses the race gracefully when two replicas claim the same message at once")
    void handlesConcurrentDuplicate() {
        // The loser of the race sees zero affected rows and must not run the handler — otherwise the
        // side effect happens twice. Crucially it must also not poison the transaction: that is a
        // property of the SQL, not of this class, so it is proved against a real database in
        // IdempotentMessageProcessorIntegrationTest rather than asserted here against a mock.
        when(repository.claim(eq("saga"), eq("m-1"), any())).thenReturn(0);
        var invocations = new AtomicInteger();

        boolean handled = processor.processOnce("saga", "m-1", "payload", value -> invocations.incrementAndGet());

        assertThat(handled).isFalse();
        assertThat(invocations).hasValue(0);
    }

    @Test
    @DisplayName("deduplication is scoped per consumer, so two consumer groups each get the message")
    void deduplicationIsScopedPerConsumer() {
        when(repository.claim(eq("saga"), eq("m-1"), any())).thenReturn(0);
        when(repository.claim(eq("audit"), eq("m-1"), any())).thenReturn(1);
        var sagaRuns = new AtomicInteger();
        var auditRuns = new AtomicInteger();

        processor.processOnce("saga", "m-1", "p", v -> sagaRuns.incrementAndGet());
        processor.processOnce("audit", "m-1", "p", v -> auditRuns.incrementAndGet());

        assertThat(sagaRuns).hasValue(0);
        assertThat(auditRuns).hasValue(1);
    }
}
