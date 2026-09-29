package dev.horizon.trends.adapter.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import dev.horizon.platform.spring.messaging.IdempotentMessageProcessor;
import dev.horizon.trends.application.dto.AnalysisResult;
import dev.horizon.trends.application.saga.ResearchSaga;
import dev.horizon.trends.domain.research.ResearchRequestId;
import dev.horizon.trends.domain.shared.Topics;

/**
 * Consumes {@code horizon.analysis.events.v1} — step 2 of the saga and the Python→Java seam
 * (ADR-0016).
 *
 * <p>Its own consumer name, separate from the ingestion listener's: the two topics are independent
 * and a message id from one must never suppress a message from the other.
 */
@Component
public class AnalysisEventsListener {

    private static final Logger log = LoggerFactory.getLogger(AnalysisEventsListener.class);

    public static final String CONSUMER = "trends.analysis-events";

    private final IdempotentMessageProcessor idempotency;
    private final EnvelopeReader envelopes;
    private final ResearchSaga saga;

    public AnalysisEventsListener(IdempotentMessageProcessor idempotency, EnvelopeReader envelopes, ResearchSaga saga) {
        this.idempotency = idempotency;
        this.envelopes = envelopes;
        this.saga = saga;
    }

    @KafkaListener(topics = Topics.ANALYSIS_EVENTS, groupId = "${horizon.kafka.consumers.analysis-events.group-id}")
    public void onMessage(String value) {
        var raw = envelopes.read(value).orElse(null);
        if (raw == null) {
            log.warn("Пропущено сообщение без конверта в топике событий анализа");
            return;
        }

        switch (raw.type()) {
            case InboundMessages.TYPE_DOMAIN_ANALYZED -> idempotency.processOnce(
                    CONSUMER,
                    raw.messageId(),
                    envelopes.payload(raw.payload(), AnalysisResult.class),
                    saga::onDomainAnalyzed);
            case InboundMessages.TYPE_DOMAIN_ANALYSIS_FAILED -> idempotency.processOnce(
                    CONSUMER,
                    raw.messageId(),
                    envelopes.payload(raw.payload(), InboundMessages.Failure.class),
                    this::handleFailure);
            case InboundMessages.TYPE_ANALYSIS_PROGRESSED -> idempotency.processOnce(
                    CONSUMER,
                    raw.messageId(),
                    envelopes.payload(raw.payload(), InboundMessages.AnalysisProgressed.class),
                    this::handleProgress);
            default -> log.debug("Сообщение типа '{}' не обрабатывается этим сервисом — подтверждено", raw.type());
        }
    }

    /**
     * Progress is advisory. The percent is reported <em>within</em> the analysis stage, and the
     * aggregate maps it onto the global bar and refuses to move it backwards (invariant I5), so
     * out-of-order delivery — normal with at-least-once — cannot make the UI jump back.
     */
    private void handleProgress(InboundMessages.AnalysisProgressed event) {
        saga.onAnalysisProgressed(ResearchRequestId.of(event.researchRequestId()), event.percent(), messageFor(event));
    }

    private static String messageFor(InboundMessages.AnalysisProgressed event) {
        if (event.message() != null && !event.message().isBlank()) {
            return event.message();
        }
        return switch (event.stage() == null ? "" : event.stage()) {
            case "EXTRACTING" -> "Выделение терминов и тем";
            case "EMBEDDING" -> "Построение семантических представлений";
            case "CLUSTERING" -> "Кластеризация технологических тем";
            case "SCORING" -> "Расчёт индикаторов зарождения";
            case "NARRATING" -> "Формирование пояснений";
            default -> "Анализ выполняется";
        };
    }

    private void handleFailure(InboundMessages.Failure event) {
        saga.onStepFailed(
                ResearchRequestId.of(event.researchRequestId()), event.code(), event.message(), event.retryable());
    }
}
