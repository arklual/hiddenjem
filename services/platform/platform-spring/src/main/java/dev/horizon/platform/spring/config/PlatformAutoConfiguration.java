package dev.horizon.platform.spring.config;

import java.time.Clock;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;

import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import dev.horizon.platform.common.id.Uuid7;

/**
 * Cross-cutting beans every Horizon service needs.
 *
 * <p>{@link Clock} is a bean on purpose: time is an input, not an ambient global. Every component
 * that needs "now" injects it, which is what makes the analysis pipeline reproducible and lets
 * tests freeze time instead of sleeping (ADR-0015).
 */
@AutoConfiguration
public class PlatformAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnMissingBean
    public Uuid7 uuid7(Clock clock) {
        return new Uuid7(clock);
    }

    /**
     * Timestamps are serialised as ISO-8601 strings, never as epoch numbers: the API contract
     * declares {@code format: date-time} and numeric timestamps silently lose sub-second precision
     * across languages.
     */
    @Bean
    public Jackson2ObjectMapperBuilderCustomizer horizonJacksonCustomizer() {
        return builder -> builder.modules(new JavaTimeModule())
                .featuresToDisable(
                        SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,
                        SerializationFeature.WRITE_DATE_TIMESTAMPS_AS_NANOSECONDS);
    }
}
