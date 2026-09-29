package dev.horizon.platform.spring.messaging;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Tunables for the transactional outbox publisher. */
@ConfigurationProperties(prefix = "horizon.outbox")
public record OutboxProperties(
        boolean enabled,
        Duration pollInterval,
        int batchSize,
        Duration baseBackoff,
        Duration maxBackoff,
        int maxAttempts,
        Duration retention) {

    public OutboxProperties {
        pollInterval = pollInterval == null ? Duration.ofMillis(200) : pollInterval;
        batchSize = batchSize <= 0 ? 100 : batchSize;
        baseBackoff = baseBackoff == null ? Duration.ofSeconds(1) : baseBackoff;
        maxBackoff = maxBackoff == null ? Duration.ofMinutes(5) : maxBackoff;
        maxAttempts = maxAttempts <= 0 ? 25 : maxAttempts;
        retention = retention == null ? Duration.ofDays(7) : retention;
    }
}
