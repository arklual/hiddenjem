package dev.horizon.trends.adapter.cache;

import java.time.Clock;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizon.trends.adapter.web.mapper.ResearchViewMapper;
import dev.horizon.trends.config.ResearchProperties;

/**
 * Wires the Redis-backed adapters.
 *
 * <p>The three adapters are plain beans rather than {@code @Component}s so that this class is the
 * single place where their Redis dependency is visible — and so that a test can replace any of them
 * without a component-scan filter.
 *
 * <p>The pub/sub listener container is the one piece that genuinely cannot work without a reachable
 * Redis (it opens a subscription at start-up), so it is the one piece behind a flag. The adapters
 * themselves degrade on their own: the cache misses, the quota opens, the broadcaster falls back to
 * local delivery. Turning {@code horizon.sse.redis-fanout} off yields a fully functional single
 * replica, which is exactly what an integration test or a laptop needs.
 */
@Configuration(proxyBeanMethods = false)
public class CacheAdapterConfiguration {

    @Bean
    public RedisReportCache reportCache(
            StringRedisTemplate redis, ObjectMapper objectMapper, ResearchProperties properties) {
        return new RedisReportCache(redis, objectMapper, properties);
    }

    @Bean
    public RedisQuotaService quotaService(StringRedisTemplate redis, Clock clock, ResearchProperties properties) {
        return new RedisQuotaService(redis, clock, properties);
    }

    @Bean
    public RedisProgressBroadcaster progressBroadcaster(
            StringRedisTemplate redis,
            SseEmitterRegistry registry,
            ProgressEvents events,
            ResearchViewMapper viewMapper,
            ObjectMapper objectMapper,
            @Value("${horizon.sse.redis-fanout:true}") boolean fanoutEnabled) {
        return new RedisProgressBroadcaster(redis, registry, events, viewMapper, objectMapper, fanoutEnabled);
    }

    /**
     * Subscribes this replica to progress produced anywhere in the cluster.
     *
     * <p>A pattern subscription rather than one channel per request: requests are created and
     * finished constantly, and subscribing/unsubscribing per request would put control-plane traffic
     * on the critical path of every analysis for no benefit — the pattern costs one subscription for
     * the lifetime of the process.
     */
    @Bean
    @ConditionalOnProperty(prefix = "horizon.sse", name = "redis-fanout", havingValue = "true", matchIfMissing = true)
    public RedisMessageListenerContainer progressListenerContainer(
            RedisConnectionFactory connectionFactory, RedisProgressBroadcaster broadcaster) {
        var container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(broadcaster, new PatternTopic(RedisProgressBroadcaster.CHANNEL_PATTERN));
        return container;
    }

    @Bean
    public RedisLock redisLock(StringRedisTemplate redis) {
        return new RedisLock(redis);
    }
}
