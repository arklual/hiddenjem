package dev.horizon.platform.spring.messaging;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Delivers outbox rows to Kafka.
 *
 * <p>Runs on a schedule and claims batches with {@code SKIP LOCKED}, so several replicas can publish
 * concurrently without coordination. Delivery is confirmed synchronously before the row is marked
 * published: if the broker acknowledgement is lost, the row stays pending and is retried, which
 * yields at-least-once semantics. Consumers deduplicate on {@code messageId}.
 *
 * <p>Messages exceeding {@code maxAttempts} are parked (left unpublished with the last error
 * recorded) and surfaced through the {@code horizon.outbox.parked} gauge and an alert, rather than
 * being dropped — losing a saga event silently is worse than a stuck queue an operator can see.
 */
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);
    private static final TypeReference<Map<String, String>> HEADERS_TYPE = new TypeReference<>() {};

    private final OutboxMessageRepository repository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final OutboxProperties properties;
    private final Clock clock;
    private final Counter published;
    private final Counter failed;

    public OutboxPublisher(
            OutboxMessageRepository repository,
            KafkaTemplate<String, String> kafkaTemplate,
            ObjectMapper objectMapper,
            OutboxProperties properties,
            Clock clock,
            MeterRegistry meterRegistry) {
        this.repository = repository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
        this.published = Counter.builder("horizon.outbox.published")
                .description("Messages successfully delivered to the broker")
                .register(meterRegistry);
        this.failed = Counter.builder("horizon.outbox.failed")
                .description("Outbox delivery attempts that failed")
                .register(meterRegistry);
        meterRegistry.gauge(
                "horizon.outbox.pending.messages", repository, OutboxMessageRepository::countByPublishedAtIsNull);
        // Возраст, а не только размер очереди. Тысяча сообщений, ушедших за секунду, — норма; одно
        // застрявшее на пять минут — отказ саги, которая его ждёт, и по счётчику он не виден.
        meterRegistry.gauge("horizon.outbox.oldest.pending.age.seconds", this, OutboxPublisher::oldestPendingAge);
        // Separate from `pending`: a growing backlog is a throughput problem that may recover,
        // whereas a non-zero parked count is a message that will never be delivered without
        // intervention. Alerting on the two together would hide the second behind the first.
        meterRegistry.gauge("horizon.outbox.parked", repository, repo -> repo.countParked(properties.maxAttempts()));
    }

    /** Seconds the oldest unpublished message has been waiting; zero when the outbox is empty. */
    private double oldestPendingAge() {
        Instant oldest = repository.oldestPendingCreatedAt();
        if (oldest == null) {
            return 0.0;
        }
        // Отрицательный возраст возможен только при разъехавшихся часах узлов; ноль честнее, чем
        // отрицательная величина, которую алерт сравнит с порогом и промолчит.
        return Math.max(0.0, java.time.Duration.between(oldest, clock.instant()).toMillis() / 1000.0);
    }

    @Scheduled(fixedDelayString = "${horizon.outbox.poll-interval:PT0.2S}")
    @Transactional
    public void publishPending() {
        var now = clock.instant();
        List<OutboxMessage> batch = repository.claimBatch(now, properties.batchSize(), properties.maxAttempts());
        if (batch.isEmpty()) {
            return;
        }
        for (OutboxMessage message : batch) {
            try {
                send(message);
                message.markPublished(clock.instant());
                published.increment();
            } catch (Exception e) {
                message.markFailed(clock.instant(), e.toString(), properties.baseBackoff(), properties.maxBackoff());
                failed.increment();
                log.warn(
                        "Outbox delivery failed for {} ({} attempt {})",
                        message.getId(),
                        message.getEventType(),
                        message.getAttempts(),
                        e);
            }
        }
        repository.saveAll(batch);
    }

    private void send(OutboxMessage message) throws Exception {
        var record = new ProducerRecord<>(message.getTopic(), null, message.getPartitionKey(), message.getPayload());
        objectMapper.readValue(message.getHeaders(), HEADERS_TYPE).forEach((key, value) -> record.headers()
                .add(key, value.getBytes(StandardCharsets.UTF_8)));
        kafkaTemplate.send(record).get(10, TimeUnit.SECONDS);
    }

    /** Housekeeping: published rows are only useful for a short forensic window. */
    @Scheduled(cron = "${horizon.outbox.cleanup-cron:0 15 3 * * *}")
    @Transactional
    public void purgeDelivered() {
        int removed = repository.deletePublishedBefore(clock.instant().minus(properties.retention()));
        if (removed > 0) {
            log.info("Purged {} delivered outbox messages", removed);
        }
    }
}
