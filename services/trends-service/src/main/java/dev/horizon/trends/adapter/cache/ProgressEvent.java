package dev.horizon.trends.adapter.cache;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One frame of the progress stream, already serialised.
 *
 * <p>The payload travels as a JSON <em>string</em> rather than as an object graph on purpose: the
 * same value is produced once, published to Redis, received by every replica and written to every
 * connected client, so serialising it once at the source avoids a parse-and-re-serialise per replica
 * per subscriber. It also guarantees that all replicas emit byte-identical frames.
 *
 * @param requestId partition of the stream — the research request being observed
 * @param eventName SSE {@code event:} field: {@code progress}, {@code completed} or {@code failed}
 * @param eventId SSE {@code id:} field, echoed back by the browser as {@code Last-Event-ID}
 * @param data JSON of {@code ResearchRequestView}
 * @param terminal whether the stream may be closed after this frame
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProgressEvent(String requestId, String eventName, String eventId, String data, boolean terminal) {

    public static final String EVENT_PROGRESS = "progress";
    public static final String EVENT_COMPLETED = "completed";
    public static final String EVENT_FAILED = "failed";
    public static final String EVENT_HEARTBEAT = "heartbeat";
}
