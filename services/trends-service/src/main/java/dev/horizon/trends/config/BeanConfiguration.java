package dev.horizon.trends.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import dev.horizon.platform.common.event.DomainEventPublisher;
import dev.horizon.trends.application.usecase.ReportAssembler;

/**
 * Beans the application layer needs but does not annotate itself.
 *
 * <p>{@link ReportAssembler} is a plain class with no Spring annotations, which is the point: it is
 * pure translation logic, unit-testable with {@code new}, and this is the only place that knows it
 * runs inside a container.
 *
 * <p>Notably <b>not</b> declared here: {@link DomainEventPublisher}. The platform auto-configures an
 * outbox-backed implementation, and declaring a bean of that type in this service would shadow it —
 * silently replacing transactional publication with whatever was declared and breaking the atomicity
 * guarantee the saga depends on. Left to the auto-configuration on purpose.
 */
@Configuration(proxyBeanMethods = false)
public class BeanConfiguration {

    @Bean
    public ReportAssembler reportAssembler() {
        return new ReportAssembler();
    }
}
