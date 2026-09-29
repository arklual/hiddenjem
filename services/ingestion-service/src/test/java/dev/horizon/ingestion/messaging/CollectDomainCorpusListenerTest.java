package dev.horizon.ingestion.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizon.ingestion.application.CollectDomainCorpusCommand;
import dev.horizon.ingestion.application.CollectDomainCorpusUseCase;
import dev.horizon.ingestion.domain.port.AnalysisMode;

/**
 * Режим анализа из команды сбора доходит до сценария: от него зависит бюджет глубокого
 * исследования, и потерянное здесь поле означало бы быстрый сбор под качественным анализом —
 * отчёт при этом выглядел бы исправным.
 */
class CollectDomainCorpusListenerTest {

    private final CollectDomainCorpusUseCase useCase = mock(CollectDomainCorpusUseCase.class);
    private final CollectDomainCorpusListener listener =
            new CollectDomainCorpusListener(useCase, new ObjectMapper());

    private static ConsumerRecord<String, String> record(String modeField) {
        String payload = "{\"researchRequestId\":\"77777777-7777-4777-8777-777777777777\",\"attempt\":1,"
                + "\"query\":\"периферийные вычисления\",\"normalizedQuery\":\"периферийные вычисления\","
                + "\"windowFrom\":\"2021-01-01\",\"windowTo\":\"2026-09-27\",\"sourceClasses\":[]"
                + modeField
                + "}";
        String envelope = "{\"type\":\"horizon.ingestion.CollectDomainCorpus\",\"payload\":" + payload + "}";
        return new ConsumerRecord<>("horizon.ingestion.commands.v1", 0, 0L, "key", envelope);
    }

    private CollectDomainCorpusCommand handled(String modeField) {
        listener.onMessage(record(modeField));
        var command = ArgumentCaptor.forClass(CollectDomainCorpusCommand.class);
        verify(useCase).handle(command.capture());
        return command.getValue();
    }

    @Test
    void качественныйРежимДоходитДоСбора() {
        CollectDomainCorpusCommand command = handled(",\"mode\":\"quality\"");

        assertThat(command.mode()).isEqualTo(AnalysisMode.QUALITY);
        assertThat(command.toCollectionRequest(100).mode()).isEqualTo(AnalysisMode.QUALITY);
        assertThat(command.toExpansionRequest("cloudlet computing", "en", 40).mode())
                .isEqualTo(AnalysisMode.QUALITY);
    }

    @Test
    void командаБезРежимаБыстрая() {
        // Отправитель старого вида: поле появилось позже, и отсутствие его — не ошибка.
        assertThat(handled("").mode()).isEqualTo(AnalysisMode.FAST);
    }

    @Test
    void неизвестныйРежимОтвергаетсяВместеСКомандой() {
        // Как неизвестный класс источника: собрать «как-нибудь» значило бы молча ответить быстрым
        // сбором на просьбу о качественном.
        listener.onMessage(record(",\"mode\":\"turbo\""));

        verify(useCase, never()).handle(any());
    }
}
