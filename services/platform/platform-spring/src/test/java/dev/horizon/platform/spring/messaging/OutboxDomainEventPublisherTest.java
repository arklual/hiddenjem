package dev.horizon.platform.spring.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import dev.horizon.platform.common.event.DomainEvent;

@ExtendWith(MockitoExtension.class)
class OutboxDomainEventPublisherTest {

    private static final Instant NOW = Instant.parse("2026-08-05T10:00:00Z");
    private static final UUID EVENT_ID = UUID.fromString("01920000-0000-7000-8000-000000000001");

    @Mock
    private OutboxMessageRepository repository;

    private ObjectMapper objectMapper;
    private OutboxDomainEventPublisher publisher;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        publisher = new OutboxDomainEventPublisher(
                repository, objectMapper, Clock.fixed(NOW, ZoneOffset.UTC), "trends-service");
    }

    private record SamplePayload(String value) {}

    private record SampleEvent(UUID eventId, Instant occurredAt) implements DomainEvent {
        @Override
        public String aggregateType() {
            return "ResearchRequest";
        }

        @Override
        public String aggregateId() {
            return "req-42";
        }

        @Override
        public String eventType() {
            return "horizon.trends.Sample";
        }

        @Override
        public String topic() {
            return "horizon.trends.events.v1";
        }

        @Override
        public String partitionKey() {
            return "req-42";
        }

        @Override
        public Object payload() {
            return new SamplePayload("hello");
        }
    }

    @Test
    @DisplayName("writes one outbox row per event with routing metadata taken from the event")
    void writesRoutingMetadata() {
        when(repository.save(any(OutboxMessage.class))).thenAnswer(invocation -> invocation.getArgument(0));

        publisher.publish(List.of(new SampleEvent(EVENT_ID, NOW)));

        var captor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(repository).save(captor.capture());
        var saved = captor.getValue();

        assertThat(saved.getId()).isEqualTo(EVENT_ID);
        assertThat(saved.getAggregateType()).isEqualTo("ResearchRequest");
        assertThat(saved.getAggregateId()).isEqualTo("req-42");
        assertThat(saved.getEventType()).isEqualTo("horizon.trends.Sample");
        assertThat(saved.getTopic()).isEqualTo("horizon.trends.events.v1");
        // Ordering of saga messages depends on this key; getting it wrong silently breaks the saga.
        assertThat(saved.getPartitionKey()).isEqualTo("req-42");
        assertThat(saved.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("serialises the envelope with the event's own payload, not its internal shape")
    void serialisesEnvelopeWithPayload() {
        when(repository.save(any(OutboxMessage.class))).thenAnswer(invocation -> invocation.getArgument(0));

        publisher.publish(List.of(new SampleEvent(EVENT_ID, NOW)));

        var captor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(repository).save(captor.capture());

        assertThat(captor.getValue().getPayload())
                .contains("\"type\":\"horizon.trends.Sample\"")
                .contains("\"source\":\"trends-service\"")
                .contains("\"payload\":{\"value\":\"hello\"}")
                .contains("\"schemaVersion\":1");
    }

    @Test
    @DisplayName("emits the headers consumers deduplicate and correlate on")
    void emitsIdempotencyHeaders() {
        when(repository.save(any(OutboxMessage.class))).thenAnswer(invocation -> invocation.getArgument(0));

        publisher.publish(List.of(new SampleEvent(EVENT_ID, NOW)));

        var captor = ArgumentCaptor.forClass(OutboxMessage.class);
        verify(repository).save(captor.capture());

        assertThat(captor.getValue().getHeaders())
                .contains("horizon-message-id")
                .contains(EVENT_ID.toString())
                .contains("horizon-correlation-id");
    }

    @Test
    @DisplayName("publishing nothing touches nothing")
    void ignoresEmptyBatch() {
        publisher.publish(List.of());
        publisher.publish((java.util.Collection<DomainEvent>) null);

        verifyNoInteractions(repository);
    }
}
