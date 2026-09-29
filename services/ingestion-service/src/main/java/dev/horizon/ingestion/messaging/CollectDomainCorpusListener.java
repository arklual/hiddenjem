package dev.horizon.ingestion.messaging;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizon.ingestion.application.CollectDomainCorpusCommand;
import dev.horizon.ingestion.application.CollectDomainCorpusUseCase;
import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.port.AnalysisMode;

/**
 * Entry point of the collection step of the saga.
 *
 * <p>Deliberately thin: unwrap the envelope, translate to a domain command, delegate. All
 * decision-making lives in the use case, which is therefore testable without Kafka.
 *
 * <p>Deduplication is <em>not</em> done here. The use case is idempotent on
 * {@code (researchRequestId, attempt)} and re-derives the same snapshot, so a redelivery is safe by
 * construction rather than by bookkeeping — the stronger of the two guarantees.
 *
 * <p>A message that cannot be parsed is acknowledged and logged rather than retried: replaying a
 * structurally invalid message forever would stall the partition for every other saga sharing it.
 * The platform's error handler routes genuine processing failures to the DLQ.
 */
@Component
public class CollectDomainCorpusListener {

    private static final Logger log = LoggerFactory.getLogger(CollectDomainCorpusListener.class);
    private static final String EXPECTED_TYPE = "horizon.ingestion.CollectDomainCorpus";

    private final CollectDomainCorpusUseCase useCase;
    private final ObjectMapper objectMapper;

    public CollectDomainCorpusListener(CollectDomainCorpusUseCase useCase, ObjectMapper objectMapper) {
        this.useCase = useCase;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(
            topics = "${horizon.kafka.topics.ingestion-commands:horizon.ingestion.commands.v1}",
            groupId = "${horizon.kafka.group-id:ingestion-service}",
            concurrency = "${horizon.kafka.concurrency:1}")
    public void onMessage(ConsumerRecord<String, String> record) {
        JsonNode envelope;
        try {
            envelope = objectMapper.readTree(record.value());
        } catch (Exception e) {
            log.error(
                    "Неразбираемое сообщение в {}-{} offset {} — пропущено",
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    e);
            return;
        }

        String type = envelope.path("type").asText("");
        if (!EXPECTED_TYPE.equals(type)) {
            log.debug("Сообщение типа '{}' не предназначено этому потребителю — пропущено", type);
            return;
        }

        CollectDomainCorpusCommand command;
        try {
            command = toCommand(envelope.path("payload"));
        } catch (RuntimeException e) {
            log.error("Некорректная команда CollectDomainCorpus в offset {}: {}", record.offset(), e.getMessage());
            return;
        }

        log.info(
                "Получена команда сбора корпуса: request={} attempt={} query='{}' mode={}",
                command.researchRequestId(),
                command.attempt(),
                command.normalizedQuery(),
                command.mode());
        useCase.handle(command);
    }

    /**
     * Предметные коды направления, если отправитель их прислал.
     *
     * <p>Отсутствие поля — не ошибка: команда старого вида остаётся действительной, и сбор по ней
     * идёт по словам запроса. Иначе выкат этой правки пришлось бы делать двумя сервисами
     * одновременно, а такого выката не бывает.
     */
    private static List<String> subjectTargets(JsonNode payload) {
        var targets = new ArrayList<String>();
        payload.path("subjectTargets").forEach(node -> {
            String value = node.asText("");
            if (!value.isBlank()) {
                targets.add(value);
            }
        });
        return List.copyOf(targets);
    }

    private CollectDomainCorpusCommand toCommand(JsonNode payload) {
        Set<SourceClass> classes = new LinkedHashSet<>();
        payload.path("sourceClasses").forEach(node -> {
            String value = node.asText("");
            if (!value.isBlank()) {
                classes.add(SourceClass.valueOf(value));
            }
        });
        return new CollectDomainCorpusCommand(
                UUID.fromString(payload.path("researchRequestId").asText()),
                payload.path("attempt").asInt(1),
                payload.path("query").asText(),
                payload.path("normalizedQuery").asText(),
                payload.hasNonNull("queryLanguage")
                        ? payload.get("queryLanguage").asText()
                        : null,
                LocalDate.parse(payload.path("windowFrom").asText()),
                LocalDate.parse(payload.path("windowTo").asText()),
                classes,
                payload.path("maxDocuments").asInt(0),
                subjectTargets(payload),
                // Отсутствие поля — быстрый режим, команда старого вида. Неизвестное значение
                // отвергается вместе с командой, как неизвестный класс источника: собрать «как-нибудь»
                // значило бы молча ответить быстрым сбором на просьбу о качественном.
                AnalysisMode.parse(payload.hasNonNull("mode") ? payload.get("mode").asText() : null));
    }
}
