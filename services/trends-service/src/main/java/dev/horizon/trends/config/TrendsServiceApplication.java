package dev.horizon.trends.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

import dev.horizon.platform.spring.messaging.MessagingAutoConfiguration;

/**
 * Entry point of {@code trends-service} — the domain core: the research saga, report assembly and
 * feedback (ADR-0004).
 *
 * <p>{@code scanBasePackages} is set explicitly because this class does not sit at the root of the
 * package tree; without it Spring would scan only {@code …trends.config} and the adapters would
 * silently not exist.
 *
 * <p>The entity and repository scans deliberately cover two packages. The outbox and deduplication
 * tables belong to the platform module but live in <em>this</em> service's schema (ADR-0006: no
 * cross-schema access), so their entities have to be registered by every service that uses them.
 * {@link MessagingAutoConfiguration#MESSAGING_PACKAGE} is referenced rather than spelled out so a
 * package rename in the platform cannot leave this behind.
 */
@SpringBootApplication(scanBasePackages = "dev.horizon.trends")
@ConfigurationPropertiesScan(basePackages = "dev.horizon.trends")
@EnableConfigurationProperties(ResearchProperties.class)
@EnableScheduling
@EntityScan(basePackages = {"dev.horizon.trends", MessagingAutoConfiguration.MESSAGING_PACKAGE})
@EnableJpaRepositories(basePackages = {"dev.horizon.trends", MessagingAutoConfiguration.MESSAGING_PACKAGE})
public class TrendsServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(TrendsServiceApplication.class, args);
    }
}
