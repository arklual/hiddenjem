package dev.horizon.trends.adapter.cache;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizon.trends.adapter.web.dto.ResearchRequestView;
import dev.horizon.trends.domain.research.ResearchStatus;

/**
 * Builds {@link ProgressEvent} frames from the published request view.
 *
 * <p>The event id is the progress timestamp in epoch milliseconds. That makes it monotonic (the
 * aggregate never moves progress backwards, invariant I5) and comparable, which is what lets a
 * reconnecting client's {@code Last-Event-ID} be answered with "you already have this" instead of a
 * redundant replay — without the server keeping a per-connection log.
 */
@Component
public class ProgressEvents {

    private final ObjectMapper objectMapper;

    public ProgressEvents(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ProgressEvent from(ResearchRequestView view) {
        var status = ResearchStatus.valueOf(view.status());
        return new ProgressEvent(
                view.id().toString(), eventNameFor(status), eventIdFor(view), serialize(view), status.isTerminal());
    }

    /**
     * The contract publishes exactly three state-carrying event types, so {@code CANCELLED} shares
     * {@code failed} with {@code FAILED}: both mean "no report is coming, stop streaming". The
     * authoritative distinction is the {@code status} field of the payload, which the client reads
     * anyway — inventing a fifth event name would break every consumer written against the contract.
     */
    private static String eventNameFor(ResearchStatus status) {
        return switch (status) {
            case COMPLETED -> ProgressEvent.EVENT_COMPLETED;
            case FAILED, CANCELLED -> ProgressEvent.EVENT_FAILED;
            case PENDING, COLLECTING, ANALYZING, ASSEMBLING -> ProgressEvent.EVENT_PROGRESS;
        };
    }

    private static String eventIdFor(ResearchRequestView view) {
        var progress = view.progress();
        long millis = progress == null || progress.updatedAt() == null
                ? 0L
                : progress.updatedAt().toEpochMilli();
        return Long.toString(millis);
    }

    /** A view that cannot be serialised is a bug in this module, not a transient failure. */
    private String serialize(ResearchRequestView view) {
        try {
            return objectMapper.writeValueAsString(view);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Не удалось сериализовать состояние запроса для SSE", e);
        }
    }
}
