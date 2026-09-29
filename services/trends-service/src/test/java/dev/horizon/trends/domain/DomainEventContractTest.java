package dev.horizon.trends.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import dev.horizon.trends.domain.report.TrendReportId;
import dev.horizon.trends.domain.report.event.TrendReportGenerated;
import dev.horizon.trends.domain.research.FailureInfo;
import dev.horizon.trends.domain.research.ResearchRequestId;
import dev.horizon.trends.domain.research.event.ResearchRequestCompleted;
import dev.horizon.trends.domain.research.event.ResearchRequestFailed;
import dev.horizon.trends.domain.research.event.ResearchRequestSubmitted;
import dev.horizon.trends.support.Fixtures;

/**
 * Контракт доменных событий: тип, агрегат, ключ раздела и полезная нагрузка.
 *
 * <p>Эти значения выглядят как подробность, но ими живёт вся асинхронная часть системы: по
 * `eventType` потребитель выбирает разбор, по `aggregateType`/`aggregateId` событие ложится в
 * outbox, по `partitionKey` брокер решает, что чему предшествует. Ошибка здесь не роняет сборку —
 * она молча уводит сообщения не туда, и находится это уже в проде.
 *
 * <p>Мутационный прогон показал по пакетам событий 0–20%: подменить любую из этих строк можно было
 * незаметно. Проверяются они здесь дословно и намеренно: тест обязан упасть при изменении значения,
 * потому что изменение значения — это несовместимое изменение контракта, а не правка кода.
 * Совпадать они должны с contracts/asyncapi/horizon-events.yaml.
 */
class DomainEventContractTest {

    private static final ResearchRequestId REQUEST_ID = ResearchRequestId.generate();
    private static final TrendReportId REPORT_ID = TrendReportId.generate();
    private static final UUID EVENT_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final Instant AT = Instant.parse("2026-08-15T12:00:00Z");

    @Nested
    @DisplayName("Запрос принят")
    class Submitted {

        private final ResearchRequestSubmitted event = new ResearchRequestSubmitted(
                EVENT_ID, AT, REQUEST_ID, Fixtures.requester(), Fixtures.query(), Fixtures.parameters(), 1);

        @Test
        void контрактСобытия() {
            assertThat(event.eventType()).isEqualTo("horizon.trends.ResearchRequestSubmitted");
            assertThat(event.aggregateType()).isEqualTo("ResearchRequest");
            assertThat(event.aggregateId()).isEqualTo(REQUEST_ID.toString());
            // Ключ раздела — сам запрос: события одного запроса обязаны идти по порядку, иначе
            // «завершён» может обогнать «принят».
            assertThat(event.partitionKey()).isEqualTo(REQUEST_ID.toString());
        }

        @Test
        void полезнаяНагрузкаНесётЗапросИпараметры() {
            var payload = (ResearchRequestSubmitted.Payload) event.payload();

            assertThat(payload.researchRequestId()).isEqualTo(REQUEST_ID.toString());
            assertThat(payload.userId()).isEqualTo(Fixtures.USER_ID.toString());
            assertThat(payload.organizationId()).isEqualTo(Fixtures.ORGANIZATION_ID.toString());
            assertThat(payload.query()).isEqualTo(Fixtures.query().raw());
            assertThat(payload.normalizedQuery()).isEqualTo(Fixtures.query().normalized());
            assertThat(payload.topN()).isEqualTo(Fixtures.parameters().topN());
            assertThat(payload.yearsWindow()).isEqualTo(Fixtures.parameters().yearsWindow());
            assertThat(payload.attempt()).isEqualTo(1);
            assertThat(payload.submittedAt()).isEqualTo(AT);
        }
    }

    @Nested
    @DisplayName("Запрос завершён")
    class Completed {

        private final ResearchRequestCompleted event = new ResearchRequestCompleted(
                EVENT_ID, AT, REQUEST_ID, REPORT_ID, Fixtures.requester(), "quantum sensing", 15, false, 42_000L);

        @Test
        void контрактСобытия() {
            assertThat(event.eventType()).isEqualTo("horizon.trends.ResearchRequestCompleted");
            assertThat(event.aggregateType()).isEqualTo("ResearchRequest");
            assertThat(event.aggregateId()).isEqualTo(REQUEST_ID.toString());
            assertThat(event.partitionKey()).isEqualTo(REQUEST_ID.toString());
        }

        @Test
        void полезнаяНагрузкаНеПуста() {
            assertThat(event.payload()).isNotNull();
            assertThat(event.eventId()).isEqualTo(EVENT_ID);
            assertThat(event.occurredAt()).isEqualTo(AT);
        }
    }

    @Nested
    @DisplayName("Запрос провален")
    class Failed {

        private final ResearchRequestFailed event = new ResearchRequestFailed(
                EVENT_ID,
                AT,
                REQUEST_ID,
                2,
                new FailureInfo(FailureInfo.NO_DOCUMENTS_FOUND, "по запросу ничего не найдено", false));

        @Test
        void контрактСобытия() {
            assertThat(event.eventType()).isEqualTo("horizon.trends.ResearchRequestFailed");
            assertThat(event.aggregateType()).isEqualTo("ResearchRequest");
            assertThat(event.aggregateId()).isEqualTo(REQUEST_ID.toString());
            assertThat(event.partitionKey()).isEqualTo(REQUEST_ID.toString());
        }

        @Test
        void полезнаяНагрузкаНесётПричинуИномерПопытки() {
            // Номер попытки и признак «можно повторить» — то, по чему сага решает, звать ли снова.
            assertThat(event.payload()).isNotNull();
            assertThat(event.attempt()).isEqualTo(2);
            assertThat(event.failure().retryable()).isFalse();
            assertThat(event.failure().code()).isEqualTo("NO_DOCUMENTS_FOUND");
        }
    }

    @Nested
    @DisplayName("Отчёт построен")
    class ReportGenerated {

        private final TrendReportGenerated event = new TrendReportGenerated(
                EVENT_ID,
                AT,
                REPORT_ID,
                REQUEST_ID,
                Fixtures.requester(),
                "quantum sensing",
                15,
                false,
                "em-1.0.0",
                Fixtures.SNAPSHOT_ID);

        @Test
        void контрактСобытия() {
            assertThat(event.eventType()).isEqualTo("horizon.trends.TrendReportGenerated");
            assertThat(event.aggregateType()).isEqualTo("TrendReport");
            assertThat(event.aggregateId()).isEqualTo(REPORT_ID.toString());
        }

        @Test
        void ключРазделаЭтоЗапрос_аНеОтчёт() {
            // Намеренно запрос: отчёт — следствие запроса, и «построен» обязан идти после
            // «принят» по тому же разделу. По отчёту это не гарантировалось бы.
            assertThat(event.partitionKey()).isEqualTo(REQUEST_ID.toString());
        }

        @Test
        void полезнаяНагрузкаНесётМетодологиюИсрезКорпуса() {
            var payload = (TrendReportGenerated.Payload) event.payload();

            assertThat(payload.reportId()).isEqualTo(REPORT_ID.toString());
            assertThat(payload.researchRequestId()).isEqualTo(REQUEST_ID.toString());
            assertThat(payload.normalizedQuery()).isEqualTo("quantum sensing");
            assertThat(payload.trendCount()).isEqualTo(15);
            assertThat(payload.partial()).isFalse();
            assertThat(payload.methodologyVersion()).isEqualTo("em-1.0.0");
            assertThat(payload.corpusSnapshotId()).isEqualTo(Fixtures.SNAPSHOT_ID.toString());
        }
    }
}
