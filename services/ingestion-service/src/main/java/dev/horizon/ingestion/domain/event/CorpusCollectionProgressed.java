package dev.horizon.ingestion.domain.event;

import java.time.Instant;
import java.util.UUID;

import dev.horizon.platform.common.event.DomainEvent;
import dev.horizon.platform.common.id.Uuid7;

/**
 * Ход сбора: сколько источников уже ответило.
 *
 * <p>Сбор — самая долгая стадия, а до этого события сага узнавала о нём только по его концу:
 * полоса хода минутами стояла на нижней границе стадии, и экран выглядел зависшим. Событие чисто
 * информационное — потеря или повтор ничего не ломают, сага не даёт проценту идти назад.
 *
 * <p>Раздел — по запросу, как у исходов сбора: ход не может обогнать «корпус собран» того же
 * запроса. {@link Payload} повторяет {@code contracts/schemas/corpus-collection-progressed.event.json}.
 */
public record CorpusCollectionProgressed(
        UUID eventId,
        Instant occurredAt,
        UUID researchRequestId,
        int attempt,
        int percent,
        String message,
        int sourcesDone,
        int sourcesTotal,
        int documentCount)
        implements DomainEvent {

    public CorpusCollectionProgressed {
        percent = Math.max(0, Math.min(100, percent));
        if (message != null && message.length() > 300) {
            message = message.substring(0, 300);
        }
    }

    public static CorpusCollectionProgressed of(
            UUID researchRequestId,
            int attempt,
            int percent,
            String message,
            int sourcesDone,
            int sourcesTotal,
            int documentCount,
            Instant occurredAt) {
        return new CorpusCollectionProgressed(
                Uuid7.randomUuid7(),
                occurredAt,
                researchRequestId,
                attempt,
                percent,
                message,
                sourcesDone,
                sourcesTotal,
                documentCount);
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
        return IngestionTopics.TYPE_CORPUS_COLLECTION_PROGRESSED;
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
        return new Payload(researchRequestId, attempt, percent, message, sourcesDone, sourcesTotal, documentCount);
    }

    /** Wire shape — see {@code corpus-collection-progressed.event.json}. */
    public record Payload(
            UUID researchRequestId,
            int attempt,
            int percent,
            String message,
            int sourcesDone,
            int sourcesTotal,
            int documentCount) {}
}
