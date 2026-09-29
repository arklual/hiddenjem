package dev.horizon.platform.spring.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Verifies the two guarantees the whole event backbone rests on, against real infrastructure:
 *
 * <ol>
 *   <li>an outbox row and the aggregate change commit atomically — a rolled-back transaction
 *       publishes nothing;
 *   <li>a committed row is delivered to Kafka exactly as written, with its idempotency headers.
 * </ol>
 *
 * <p>These cannot be proved with mocks: the interesting behaviour is transactional, and a mocked
 * repository would happily "roll back" a write that a real database would have kept.
 */
@Tag("integration")
@Testcontainers
@SpringBootTest(classes = OutboxPublisherIntegrationTest.TestApp.class)
class OutboxPublisherIntegrationTest {

    private static final String TOPIC = "horizon.test.events.v1";
    private static final Instant NOW = Instant.parse("2026-08-05T10:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.1"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("horizon.outbox.poll-interval", () -> "PT0.1S");
        registry.add("spring.application.name", () -> "platform-test");
        registry.add("horizon.security.enabled", () -> "false");
    }

    @Configuration
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = OutboxMessage.class)
    @EnableJpaRepositories(basePackageClasses = OutboxMessageRepository.class)
    static class TestApp {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @TestConfiguration
    static class Meters {
        @Bean
        SimpleMeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    @Autowired
    private OutboxMessageRepository repository;

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransactionTemplate transactions;

    private OutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        publisher = new OutboxPublisher(
                repository,
                kafkaTemplate,
                objectMapper,
                new OutboxProperties(
                        true,
                        Duration.ofMillis(100),
                        100,
                        Duration.ofSeconds(1),
                        Duration.ofMinutes(5),
                        25,
                        Duration.ofDays(7)),
                Clock.fixed(NOW, ZoneOffset.UTC),
                new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("a committed outbox row reaches Kafka with its idempotency headers")
    void deliversCommittedMessage() {
        UUID messageId = UUID.randomUUID();
        transactions.executeWithoutResult(status -> repository.save(row(messageId, "payload-1")));

        publisher.publishPending();

        List<ConsumerRecord<String, String>> received = consume(1);
        assertThat(received).hasSize(1);
        assertThat(received.get(0).key()).isEqualTo("agg-1");
        assertThat(received.get(0).value()).contains("payload-1");
        assertThat(header(received.get(0), "horizon-message-id")).isEqualTo(messageId.toString());

        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(repository.findById(messageId))
                .get()
                .extracting(OutboxMessage::getPublishedAt)
                .isNotNull());
    }

    @Test
    @DisplayName("a rolled-back transaction publishes nothing — atomicity holds without 2PC")
    void rolledBackTransactionPublishesNothing() {
        UUID messageId = UUID.randomUUID();

        try {
            transactions.executeWithoutResult(status -> {
                repository.save(row(messageId, "must-not-appear"));
                throw new IllegalStateException("simulated business failure");
            });
        } catch (IllegalStateException expected) {
            // intentional
        }

        assertThat(repository.findById(messageId)).isEmpty();
        publisher.publishPending();
        assertThat(consume(0)).isEmpty();
    }

    @Test
    @DisplayName("a message is claimed once: a second publisher run does not re-deliver it")
    void doesNotRedeliverPublishedMessage() {
        UUID messageId = UUID.randomUUID();
        transactions.executeWithoutResult(status -> repository.save(row(messageId, "once")));

        publisher.publishPending();
        publisher.publishPending();

        assertThat(consume(1)).hasSize(1);
    }

    private OutboxMessage row(UUID id, String payload) {
        return new OutboxMessage(
                id,
                "TestAggregate",
                "agg-1",
                "horizon.test.Sample",
                TOPIC,
                "agg-1",
                "{\"payload\":\"%s\"}".formatted(payload),
                "{\"horizon-message-id\":\"%s\"}".formatted(id),
                NOW);
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Drains the topic, waiting briefly for {@code expected} records so an empty result is meaningful. */
    private List<ConsumerRecord<String, String>> consume(int expected) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG,
                "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                false);
        var collected = new CopyOnWriteArrayList<ConsumerRecord<String, String>>();
        try (var consumer = new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(TOPIC));
            long deadline = System.currentTimeMillis() + 10_000;
            while (System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(collected::add);
                if (collected.size() >= expected && (expected > 0 || System.currentTimeMillis() > deadline - 7_000)) {
                    break;
                }
            }
        }
        return collected;
    }
}
