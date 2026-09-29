package dev.horizon.trends.application.port;

import java.util.Map;
import java.util.UUID;

/**
 * Outbound port for issuing commands to other services (ADR-0004).
 *
 * <p>Separate from {@code DomainEventPublisher} on purpose: a fact ("this happened") and an
 * instruction ("do this") have different semantics, different consumers and different compatibility
 * rules, even though both travel over the same transactional outbox.
 */
public interface CommandSender {

    void send(OutboundCommand command);

    /**
     * @param partitionKey the research request id — guarantees saga messages stay ordered
     */
    record OutboundCommand(
            UUID messageId,
            String type,
            String topic,
            String partitionKey,
            Object payload,
            Map<String, String> headers,
            String causationId) {

        /**
         * Команда, порождённая внешним действием, а не сообщением: причины у неё нет.
         *
         * <p>`causationId` объявлен в контракте конверта как «messageId сообщения-причины» и до сих
         * пор всегда был `null` — поле существовало только в описании. Здесь `null` честен: запрос
         * пользователя не является сообщением.
         */
        public static OutboundCommand of(String type, String topic, String partitionKey, Object payload) {
            return causedBy(null, type, topic, partitionKey, payload);
        }

        /** Команда, порождённая сообщением: цепочка «событие → команда» восстановима без трассы. */
        public static OutboundCommand causedBy(
                String causationId, String type, String topic, String partitionKey, Object payload) {
            return new OutboundCommand(
                    dev.horizon.platform.common.id.Uuid7.randomUuid7(),
                    type,
                    topic,
                    partitionKey,
                    payload,
                    Map.of(),
                    causationId);
        }
    }
}
