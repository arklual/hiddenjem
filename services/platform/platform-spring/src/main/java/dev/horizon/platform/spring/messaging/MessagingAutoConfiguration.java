package dev.horizon.platform.spring.messaging;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.util.backoff.ExponentialBackOff;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Wires the outbox, idempotent consumption and dead-letter handling for every service.
 *
 * <p>Everything here is opt-out via {@code horizon.outbox.enabled} / {@code horizon.messaging.enabled}
 * so that slice tests can run without a broker.
 */
@AutoConfiguration
@EnableScheduling
@EnableConfigurationProperties(OutboxProperties.class)
@ConditionalOnClass(KafkaTemplate.class)
public class MessagingAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MessagingAutoConfiguration.class);

    /** Package that services must include in {@code @EntityScan} / {@code @EnableJpaRepositories}. */
    public static final String MESSAGING_PACKAGE = "dev.horizon.platform.spring.messaging";

    @Bean
    @ConditionalOnMissingBean(name = "outboxKafkaTemplate")
    public KafkaTemplate<String, String> outboxKafkaTemplate(KafkaProperties kafkaProperties) {
        Map<String, Object> config = new HashMap<>(kafkaProperties.buildProducerProperties(null));
        config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        // Durability over latency: the outbox already absorbs producer slowness.
        config.putIfAbsent(ProducerConfig.ACKS_CONFIG, "all");
        config.putIfAbsent(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        config.putIfAbsent(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config));
    }

    @Bean
    @ConditionalOnMissingBean
    public OutboxDomainEventPublisher outboxDomainEventPublisher(
            OutboxMessageRepository repository,
            ObjectMapper objectMapper,
            Clock clock,
            org.springframework.core.env.Environment environment) {
        String serviceName = environment.getProperty("spring.application.name", "horizon-service");
        return new OutboxDomainEventPublisher(repository, objectMapper, clock, serviceName);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "horizon.outbox", name = "enabled", havingValue = "true", matchIfMissing = true)
    public OutboxPublisher outboxPublisher(
            OutboxMessageRepository repository,
            KafkaTemplate<String, String> outboxKafkaTemplate,
            ObjectMapper objectMapper,
            OutboxProperties properties,
            Clock clock,
            MeterRegistry meterRegistry) {
        return new OutboxPublisher(repository, outboxKafkaTemplate, objectMapper, properties, clock, meterRegistry);
    }

    /**
     * Publishes {@code horizon_kafka_consumer_lag_seconds} — how old a record is when we start
     * handling it.
     *
     * <p>Lag in records (what the broker exports) answers a different question than lag in seconds.
     * Five thousand records behind is fine for a topic doing ten thousand a second and an outage for
     * one doing ten. NFR-P5 states the budget in seconds, so the metric has to be in seconds, and the
     * only place that knows both the record timestamp and the moment of handling is the consumer.
     *
     * <p>A gauge per (group, topic) rather than a timer: the alert takes {@code max by (...)} of the
     * current lag, and a distribution would answer "how bad has it been" when the question is "how
     * far behind are we right now".
     */
    @Bean
    @ConditionalOnMissingBean
    public RecordInterceptor<String, String> consumerLagInterceptor(MeterRegistry meterRegistry, Clock clock) {
        var gauges = new ConcurrentHashMap<String, AtomicLong>();
        return new RecordInterceptor<>() {
            @Override
            public ConsumerRecord<String, String> intercept(
                    ConsumerRecord<String, String> record, Consumer<String, String> consumer) {
                String group = consumer.groupMetadata().groupId();
                AtomicLong millis = gauges.computeIfAbsent(group + "\u0000" + record.topic(), key -> {
                    var holder = new AtomicLong();
                    Gauge.builder("horizon.kafka.consumer.lag.seconds", holder, value -> value.get() / 1000.0)
                            .description("Age of the record being handled, in seconds")
                            .tag("group", group)
                            .tag("topic", record.topic())
                            .register(meterRegistry);
                    return holder;
                });
                // Отрицательный лаг означает разъехавшиеся часы брокера и потребителя; ноль честнее,
                // чем отрицательная величина, которую алерт сравнит с порогом и промолчит.
                millis.set(Math.max(0L, clock.millis() - record.timestamp()));
                return record;
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotentMessageProcessor idempotentMessageProcessor(ProcessedMessageRepository repository, Clock clock) {
        return new IdempotentMessageProcessor(repository, clock);
    }

    /**
     * Routes poison messages to {@code <topic>.dlq} after bounded retries.
     *
     * <p>The partition is preserved so that ordering-related failures stay analysable, and the
     * original headers (including trace context) travel with the message so an operator can pull up
     * the exact trace that produced it.
     */
    @Bean
    @ConditionalOnMissingBean
    public DefaultErrorHandler kafkaErrorHandler(
            KafkaTemplate<String, String> outboxKafkaTemplate, MeterRegistry meterRegistry) {
        var recoverer = new DeadLetterPublishingRecoverer(outboxKafkaTemplate, (record, exception) -> {
            // Считается здесь, а не у потребителя: в мёртвую очередь сообщение попадает
            // ровно отсюда, и счётчик, расставленный по обработчикам, разъехался бы с ней
            // при первом же новом потребителе. Тег — исходный топик: «что-то попало в DLQ»
            // без указания откуда заставляет оператора искать вручную.
            meterRegistry
                    .counter("horizon.dlq.messages", "topic", record.topic())
                    .increment();
            return new TopicPartition(record.topic() + ".dlq", record.partition());
        });
        var backOff = new ExponentialBackOff(1_000L, 2.0);
        backOff.setMaxElapsedTime(60_000L);
        var handler = new DefaultErrorHandler(recoverer, backOff);
        handler.setAckAfterHandle(true);
        handler.addNotRetryableExceptions(
                IllegalArgumentException.class, com.fasterxml.jackson.core.JsonProcessingException.class);
        handler.setRetryListeners((record, ex, attempt) -> log.warn(
                "Retrying record from {}-{} offset {} (attempt {})",
                record.topic(),
                record.partition(),
                record.offset(),
                attempt,
                ex));
        return handler;
    }
}
