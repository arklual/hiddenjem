package dev.horizon.trends.adapter.messaging;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import dev.horizon.platform.spring.messaging.IdempotentMessageProcessor;
import dev.horizon.trends.application.saga.ResearchSaga;
import dev.horizon.trends.domain.research.ResearchRequestId;
import dev.horizon.trends.domain.shared.Topics;

/**
 * Consumes {@code horizon.ingestion.events.v1} — step 1 of the saga.
 *
 * <p>Every handler runs through {@link IdempotentMessageProcessor} with a consumer name unique to
 * this listener. Uniqueness per listener, not per service, is what allows the same message id to be
 * processed once by each consumer that legitimately needs it.
 *
 * <p>Deduplication here is an optimisation, not the correctness mechanism: the aggregate treats a
 * transition to its current state as a no-op, so a duplicate that slipped through would change
 * nothing anyway (see {@code ResearchSaga}). Skipping the work is simply cheaper than redoing it.
 */
@Component
public class IngestionEventsListener {

    private static final Logger log = LoggerFactory.getLogger(IngestionEventsListener.class);

    /** Stored in {@code processed_messages.consumer}; must stay ≤ 64 characters and stable forever. */
    public static final String CONSUMER = "trends.ingestion-events";

    private final IdempotentMessageProcessor idempotency;
    private final EnvelopeReader envelopes;
    private final ResearchSaga saga;

    public IngestionEventsListener(
            IdempotentMessageProcessor idempotency, EnvelopeReader envelopes, ResearchSaga saga) {
        this.idempotency = idempotency;
        this.envelopes = envelopes;
        this.saga = saga;
    }

    @KafkaListener(topics = Topics.INGESTION_EVENTS, groupId = "${horizon.kafka.consumers.ingestion-events.group-id}")
    public void onMessage(String value) {
        var raw = envelopes.read(value).orElse(null);
        if (raw == null) {
            // Not an envelope: retrying cannot make it become one, so acknowledge and move on.
            // Blocking the partition on an unparseable record would stall every other saga on it.
            log.warn("Пропущено сообщение без конверта в топике событий сбора");
            return;
        }

        switch (raw.type()) {
            case InboundMessages.TYPE_CORPUS_COLLECTED -> idempotency.processOnce(
                    CONSUMER,
                    raw.messageId(),
                    envelopes.payload(raw.payload(), InboundMessages.CorpusCollected.class),
                    payload -> handleCollected(raw.messageId(), payload));
            case InboundMessages.TYPE_CORPUS_COLLECTION_FAILED -> idempotency.processOnce(
                    CONSUMER,
                    raw.messageId(),
                    envelopes.payload(raw.payload(), InboundMessages.Failure.class),
                    this::handleFailure);
            case InboundMessages.TYPE_CORPUS_COLLECTION_PROGRESSED -> idempotency.processOnce(
                    CONSUMER,
                    raw.messageId(),
                    envelopes.payload(raw.payload(), InboundMessages.CorpusCollectionProgressed.class),
                    this::handleProgress);
            default -> log.debug("Сообщение типа '{}' не обрабатывается этим сервисом — подтверждено", raw.type());
        }
    }

    private void handleCollected(String causedBy, InboundMessages.CorpusCollected event) {
        saga.onCorpusCollected(
                ResearchRequestId.of(event.researchRequestId()),
                UUID.fromString(event.snapshotId()),
                event.documentCount(),
                event.sourcesUsedOrEmpty(),
                event.unavailableSourcesOrEmpty(),
                // Причина команды анализа — сообщение о собранном корпусе. Контракт конверта
                // объявляет это поле, и до сих пор оно всегда было пустым.
                causedBy);
    }

    /** Ход сбора — как ход анализа: процент внутри стадии, назад агрегат его не пускает. */
    private void handleProgress(InboundMessages.CorpusCollectionProgressed event) {
        saga.onCollectionProgressed(
                ResearchRequestId.of(event.researchRequestId()), event.percent(), event.message());
    }

    private void handleFailure(InboundMessages.Failure event) {
        saga.onStepFailed(
                ResearchRequestId.of(event.researchRequestId()), event.code(), event.message(), event.retryable());
    }
}
