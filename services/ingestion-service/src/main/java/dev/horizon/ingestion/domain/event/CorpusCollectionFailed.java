package dev.horizon.ingestion.domain.event;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import dev.horizon.platform.common.event.DomainEvent;
import dev.horizon.platform.common.id.Uuid7;

/**
 * Collection could not produce a usable corpus.
 *
 * <p>Note the asymmetry with {@link CorpusCollected}: a run that lost <em>some</em> sources is a
 * success flagged {@code partial}, never a failure (BR-C7). This event is reserved for "there is
 * nothing to analyse" — every source down, or zero documents matched.
 *
 * <p>{@link Payload} mirrors the shared {@code contracts/schemas/failure.event.json}; {@code
 * retryable} tells the saga whether a new attempt could plausibly succeed.
 */
public record CorpusCollectionFailed(
        UUID eventId,
        Instant occurredAt,
        UUID researchRequestId,
        int attempt,
        String code,
        String message,
        boolean retryable,
        Map<String, Object> details)
        implements DomainEvent {

    /** No source could be reached; a later attempt may well succeed. */
    public static final String CODE_ALL_SOURCES_UNAVAILABLE = "ALL_SOURCES_UNAVAILABLE";

    /** Sources answered, but nothing matched the query and window; retrying changes nothing. */
    public static final String CODE_NO_DOCUMENTS_FOUND = "NO_DOCUMENTS_FOUND";

    /** No connector is enabled for the requested source classes. */
    public static final String CODE_NO_SOURCES_ENABLED = "NO_SOURCES_ENABLED";

    public CorpusCollectionFailed {
        details = details == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(details));
        if (message != null && message.length() > 2000) {
            message = message.substring(0, 2000);
        }
    }

    public static CorpusCollectionFailed of(
            UUID researchRequestId,
            int attempt,
            String code,
            String message,
            boolean retryable,
            Map<String, Object> details,
            Instant occurredAt) {
        return new CorpusCollectionFailed(
                Uuid7.randomUuid7(), occurredAt, researchRequestId, attempt, code, message, retryable, details);
    }

    @Override
    public String aggregateType() {
        return "CorpusSnapshot";
    }

    @Override
    public String aggregateId() {
        return researchRequestId.toString();
    }

    @Override
    public String eventType() {
        return IngestionTopics.TYPE_CORPUS_COLLECTION_FAILED;
    }

    @Override
    public String topic() {
        return IngestionTopics.EVENTS;
    }

    @Override
    public String partitionKey() {
        return researchRequestId.toString();
    }

    @Override
    public Object payload() {
        return new Payload(researchRequestId, attempt, code, message, retryable, details);
    }

    /** Wire shape — see {@code failure.event.json}. */
    public record Payload(
            UUID researchRequestId,
            int attempt,
            String code,
            String message,
            boolean retryable,
            Map<String, Object> details) {}
}
