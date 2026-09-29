package dev.horizon.gateway.config;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

import dev.horizon.gateway.filter.RateLimitWebFilter;

/** Wiring for edge-only concerns. */
@Configuration
public class GatewayBeansConfiguration {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnProperty(prefix = "horizon.gateway", name = "rate-limit-enabled", matchIfMissing = true)
    public RateLimitWebFilter rateLimitWebFilter(
            ReactiveStringRedisTemplate redis, GatewayProperties properties, Clock clock) {
        return new RateLimitWebFilter(redis, properties, clock);
    }
}
