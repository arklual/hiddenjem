package dev.horizon.trends.adapter.cache;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Holds the SSE connections attached to <em>this</em> replica.
 *
 * <p>Cross-replica fan-out is not this class's job: {@link RedisProgressBroadcaster} publishes every
 * transition to Redis and every replica — including the one that produced it — receives it back and
 * calls {@link #deliver}. That single path means a client connected to replica A sees progress
 * produced by replica B, and there is no "local" special case that could behave differently from the
 * remote one.
 *
 * <p>Failure model: an emitter that throws is removed, never retried. The stream is an optimisation
 * over polling (ADR-0012); dropping a slow or dead client is strictly better than letting it hold a
 * container thread or grow an unbounded buffer.
 */
@Component
public class SseEmitterRegistry {

    private static final Logger log = LoggerFactory.getLogger(SseEmitterRegistry.class);

    /** Per-request subscriber sets. A request typically has one subscriber; a shared link may have several. */
    private final Map<String, Set<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    private final long timeoutMillis;

    public SseEmitterRegistry(@Value("${horizon.sse.timeout-ms:600000}") long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * Attaches a client to a request's stream.
     *
     * @param lastEventId value of the {@code Last-Event-ID} header on reconnect, or {@code null}
     * @param snapshot the request's current state, sent immediately unless the client already has it
     */
    public SseEmitter register(String requestId, String lastEventId, ProgressEvent snapshot) {
        var emitter = new SseEmitter(timeoutMillis);
        // Registered before the first send so that a client which disconnects during the snapshot
        // is still cleaned up by the callbacks rather than leaking an entry.
        subscribers
                .computeIfAbsent(requestId, key -> ConcurrentHashMap.newKeySet())
                .add(emitter);
        emitter.onCompletion(() -> remove(requestId, emitter));
        emitter.onTimeout(() -> {
            remove(requestId, emitter);
            emitter.complete();
        });
        emitter.onError(error -> {
            remove(requestId, emitter);
            emitter.complete();
        });

        if (!alreadyDelivered(lastEventId, snapshot)) {
            send(requestId, emitter, snapshot);
        }
        if (snapshot.terminal()) {
            // Nothing further can arrive (invariant I6): close instead of holding the connection
            // open until the timeout and provoking a pointless browser reconnect.
            complete(requestId, emitter);
        }
        return emitter;
    }

    /** Writes an event to every local subscriber of the request. */
    public void deliver(ProgressEvent event) {
        Collection<SseEmitter> targets = subscribers.get(event.requestId());
        if (targets == null || targets.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : List.copyOf(targets)) {
            send(event.requestId(), emitter, event);
            if (event.terminal()) {
                complete(event.requestId(), emitter);
            }
        }
    }

    /**
     * Keep-alive (ADR-0012: every 15 s).
     *
     * <p>Not cosmetic: intermediate proxies close idle connections, and a stalled analysis can
     * legitimately produce no progress for a minute. The heartbeat also doubles as liveness
     * detection — a write to a client that has gone away fails here and prunes the entry.
     */
    @Scheduled(fixedRateString = "${horizon.sse.heartbeat-interval-ms:15000}")
    public void heartbeat() {
        subscribers.forEach((requestId, emitters) -> {
            for (SseEmitter emitter : List.copyOf(emitters)) {
                try {
                    // Payload is required, not decorative: a frame with no `data:` line is
                    // dropped by a spec-conformant client parser, so a comment-only heartbeat
                    // never reaches the browser's silence timer and the stream looks dead.
                    emitter.send(SseEmitter.event()
                            .name(ProgressEvent.EVENT_HEARTBEAT)
                            .comment("keep-alive")
                            .data("{}", MediaType.APPLICATION_JSON));
                } catch (IOException | IllegalStateException e) {
                    log.debug("Heartbeat failed for request {}, dropping subscriber", requestId);
                    remove(requestId, emitter);
                }
            }
        });
    }

    public int subscriberCount(String requestId) {
        return subscribers.getOrDefault(requestId, Set.of()).size();
    }

    public int streamCount() {
        return subscribers.size();
    }

    /**
     * A reconnecting browser replays its last id; if the state has not moved on since, re-sending it
     * would make the UI flicker through an update it already applied.
     */
    private static boolean alreadyDelivered(String lastEventId, ProgressEvent snapshot) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return false;
        }
        try {
            return Long.parseLong(lastEventId.trim()) >= Long.parseLong(snapshot.eventId());
        } catch (NumberFormatException e) {
            // An id from an older deployment format: replay rather than skip. Showing the state
            // twice is harmless; not showing it at all is not.
            return false;
        }
    }

    private void send(String requestId, SseEmitter emitter, ProgressEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .id(event.eventId())
                    .name(event.eventName())
                    .data(event.data(), MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            log.debug("SSE delivery failed for request {}: {}", requestId, e.getMessage());
            remove(requestId, emitter);
        }
    }

    private void complete(String requestId, SseEmitter emitter) {
        remove(requestId, emitter);
        try {
            emitter.complete();
        } catch (IllegalStateException e) {
            log.trace("Emitter for {} was already closed", requestId);
        }
    }

    /** Removes the empty set as well, so a long-running instance does not accumulate dead keys. */
    private void remove(String requestId, SseEmitter emitter) {
        subscribers.computeIfPresent(requestId, (key, emitters) -> {
            emitters.remove(emitter);
            return emitters.isEmpty() ? null : emitters;
        });
    }
}
