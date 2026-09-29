package dev.horizon.trends.adapter.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.horizon.trends.application.port.CommandSender;

/**
 * `causationId` объявлен в `contracts/schemas/envelope.json` как «messageId сообщения-причины» — и
 * всегда был `null`: оба отправителя передавали в это поле литеральный null. Поле существовало
 * только в описании, а потребитель, построенный по контракту, восстанавливал бы цепочку
 * «событие → команда» по пустому значению.
 *
 * <p>Проверяется не сериализация, а решение: у команды, порождённой сообщением, причина есть; у
 * команды, порождённой действием пользователя, её нет и быть не должно — HTTP-запрос не сообщение.
 */
class CausationReachesTheEnvelopeTest {

    @Test
    @DisplayName("команда, порождённая сообщением, несёт его идентификатор")
    void aCommandCausedByAMessageCarriesItsId() {
        var command = CommandSender.OutboundCommand.causedBy(
                "0192f0b1-3c4d-7000-8000-000000000001",
                "horizon.analysis.AnalyzeDomain",
                "horizon.analysis.commands.v1",
                "request-1",
                new Object());

        assertThat(command.causationId()).isEqualTo("0192f0b1-3c4d-7000-8000-000000000001");
    }

    @Test
    @DisplayName("команда от действия пользователя причины не имеет — и это честный null")
    void aCommandFromAUserActionHasNoCause() {
        var command = CommandSender.OutboundCommand.of(
                "horizon.ingestion.CollectDomainCorpus", "horizon.ingestion.commands.v1", "request-1", new Object());

        assertThat(command.causationId()).isNull();
    }
}
