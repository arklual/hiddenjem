package dev.horizon.ingestion.domain.event;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import dev.horizon.ingestion.domain.snapshot.CorpusSnapshot;
import dev.horizon.platform.common.event.DomainEvent;
import dev.horizon.platform.common.id.Uuid7;

/**
 * The corpus for one research request is ready — the saga's cue to start the analysis.
 *
 * <p>Partitioned by {@code researchRequestId} so every event of one saga is strictly ordered
 * (asyncapi conventions). {@link Payload} mirrors
 * {@code contracts/schemas/corpus-collected.event.json} exactly.
 */
public record CorpusCollected(
        UUID eventId, Instant occurredAt, UUID researchRequestId, int attempt, CorpusSnapshot snapshot)
        implements DomainEvent {

    public static CorpusCollected of(UUID researchRequestId, int attempt, CorpusSnapshot snapshot, Instant occurredAt) {
        return new CorpusCollected(Uuid7.randomUuid7(), occurredAt, researchRequestId, attempt, snapshot);
    }

    @Override
    public String aggregateType() {
        return "CorpusSnapshot";
    }

    @Override
    public String aggregateId() {
        return snapshot.id().toString();
    }

    @Override
    public String eventType() {
        return IngestionTopics.TYPE_CORPUS_COLLECTED;
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
        return new Payload(
                researchRequestId,
                attempt,
                snapshot.id(),
                snapshot.documentCount(),
                snapshot.sourcesUsed(),
                snapshot.unavailableSources(),
                snapshot.partial(),
                snapshot.windowFrom(),
                snapshot.windowTo(),
                snapshot.contentHash());
    }

    /** Wire shape — see {@code corpus-collected.event.json}. */
    public record Payload(
            UUID researchRequestId,
            int attempt,
            UUID snapshotId,
            int documentCount,
            List<String> sourcesUsed,
            List<String> unavailableSources,
            boolean partial,
            LocalDate windowFrom,
            LocalDate windowTo,
            String contentHash) {}
}
