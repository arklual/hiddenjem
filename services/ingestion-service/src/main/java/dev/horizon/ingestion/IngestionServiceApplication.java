package dev.horizon.ingestion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import dev.horizon.platform.spring.messaging.MessagingAutoConfiguration;

/**
 * Source ingestion service.
 *
 * <p>The entity and repository scans explicitly include the platform messaging package: the
 * transactional outbox and the consumer deduplication table live there and must be picked up, while
 * this service's own persistence is plain JDBC.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@EnableAsync
@EntityScan(basePackages = {"dev.horizon.ingestion", MessagingAutoConfiguration.MESSAGING_PACKAGE})
@EnableJpaRepositories(basePackages = {"dev.horizon.ingestion", MessagingAutoConfiguration.MESSAGING_PACKAGE})
public class IngestionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(IngestionServiceApplication.class, args);
    }
}
