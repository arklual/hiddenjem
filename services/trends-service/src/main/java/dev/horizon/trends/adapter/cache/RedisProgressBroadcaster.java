package dev.horizon.trends.adapter.cache;

import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizon.trends.adapter.web.mapper.ResearchViewMapper;
import dev.horizon.trends.application.port.ProgressBroadcaster;
import dev.horizon.trends.domain.research.ResearchRequest;

/**
 * Fans progress out across replicas through Redis pub/sub (ADR-0011, ADR-0012).
 *
 * <p>The problem it solves: a browser's SSE connection lands on whichever replica the load balancer
 * picked, while the saga step that produces progress runs on whichever replica consumed the Kafka
 * message. Those are usually not the same instance. Publishing every transition on
 * {@code sse:progress:{requestId}} and having <em>every</em> replica — including the publisher —
 * consume it back gives one uniform delivery path instead of a local shortcut plus a remote path that
 * could drift apart.
 *
 * <p>Delivery is best effort by design. If Redis is unreachable the event is delivered to this
 * replica's own subscribers and the rest fall back to polling, which the client does automatically;
 * a progress notification is never the authoritative state.
 *
 * <p>Ordering note: {@code broadcast} is called inside the saga's transaction, so a subscriber can in
 * principle observe {@code COMPLETED} a few milliseconds before the row commits. Harmless here — the
 * client re-reads through {@code GET}, which is transactional — and the alternative (an
 * after-commit hook) would delay every progress update by the commit latency.
 */
public class RedisProgressBroadcaster implements ProgressBroadcaster, MessageListener {

    private static final Logger log = LoggerFactory.getLogger(RedisProgressBroadcaster.class);

    public static final String CHANNEL_PREFIX = "sse:progress:";
    public static final String CHANNEL_PATTERN = CHANNEL_PREFIX + "*";

    private final StringRedisTemplate redis;
    private final SseEmitterRegistry registry;
    private final ProgressEvents events;
    private final ResearchViewMapper viewMapper;
    private final ObjectMapper objectMapper;
    private final boolean fanoutEnabled;

    public RedisProgressBroadcaster(
            StringRedisTemplate redis,
            SseEmitterRegistry registry,
            ProgressEvents events,
            ResearchViewMapper viewMapper,
            ObjectMapper objectMapper,
            boolean fanoutEnabled) {
        this.redis = redis;
        this.registry = registry;
        this.events = events;
        this.viewMapper = viewMapper;
        this.objectMapper = objectMapper;
        this.fanoutEnabled = fanoutEnabled;
    }

    @Override
    public void broadcast(ResearchRequest request) {
        ProgressEvent event;
        try {
            event = events.from(viewMapper.toView(request));
        } catch (RuntimeException e) {
            // Building a notification must never fail the saga transaction that produced the state.
            log.warn("Не удалось подготовить событие прогресса для запроса {}", request.id(), e);
            return;
        }

        if (!fanoutEnabled) {
            registry.deliver(event);
            return;
        }
        try {
            redis.convertAndSend(CHANNEL_PREFIX + event.requestId(), objectMapper.writeValueAsString(event));
        } catch (Exception e) {
            log.warn(
                    "Redis недоступен, прогресс запроса {} разослан только локально: {}", request.id(), e.getMessage());
            registry.deliver(event);
        }
    }

    /** Inbound side of the same channel: hands the event to this replica's local subscribers. */
    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            var event =
                    objectMapper.readValue(new String(message.getBody(), StandardCharsets.UTF_8), ProgressEvent.class);
            registry.deliver(event);
        } catch (Exception e) {
            // A malformed frame from another replica is not worth failing the listener thread over.
            log.warn("Некорректное событие прогресса в канале Redis: {}", e.getMessage());
        }
    }
}
