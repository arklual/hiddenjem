package dev.horizon.ingestion.domain.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import dev.horizon.ingestion.domain.snapshot.CorpusSnapshot;

/**
 * Контракт событий сбора: тема, тип, агрегат и ключ раздела.
 *
 * <p>Ключ раздела здесь — не подробность, а условие правильности саги. И «корпус собран», и «сбор
 * не удался» разделяются по `researchRequestId`, хотя агрегат у них снимок: только так все события
 * одного запроса приходят строго по порядку, и сага не начинает анализ по отчёту о неудаче, пришедшему
 * позже успеха.
 *
 * <p>Имена тем и типов вынесены константами и совпадают с
 * `contracts/asyncapi/horizon-events.yaml` дословно. Опечатка здесь создаёт тему, которую никто не
 * читает, — без единой ошибки в журналах.
 */
class IngestionEventContractTest {

    private static final Instant AT = Instant.parse("2026-08-15T12:00:00Z");
    private static final UUID REQUEST = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID SNAPSHOT = UUID.fromString("22222222-2222-4222-8222-222222222222");

    private static CorpusSnapshot snapshot(List<String> unavailable) {
        return CorpusSnapshot.assemble(
                SNAPSHOT,
                "quantum sensing",
                LocalDate.of(2019, 1, 1),
                LocalDate.of(2026, 1, 1),
                List.of(UUID.randomUUID(), UUID.randomUUID()),
                List.of("openalex", "arxiv"),
                unavailable);
    }

    @Nested
    @DisplayName("Корпус собран")
    class Collected {

        private final CorpusCollected event = CorpusCollected.of(REQUEST, 1, snapshot(List.of()), AT);

        @Test
        void контракт() {
            assertThat(event.eventType()).isEqualTo("horizon.ingestion.CorpusCollected");
            assertThat(event.topic()).isEqualTo("horizon.ingestion.events.v1");
            assertThat(event.aggregateType()).isEqualTo("CorpusSnapshot");
            assertThat(event.aggregateId()).isEqualTo(SNAPSHOT.toString());
        }

        @Test
        void разделПоЗапросу_аНеПоСнимку() {
            // Агрегат — снимок, раздел — запрос: иначе «собран» и «не удался» одного запроса могли
            // бы разъехаться по разделам и прийти саге в обратном порядке.
            assertThat(event.partitionKey()).isEqualTo(REQUEST.toString());
        }

        @Test
        void нагрузкаПовторяетСнимок() {
            var payload = (CorpusCollected.Payload) event.payload();

            assertThat(payload.researchRequestId()).isEqualTo(REQUEST);
            assertThat(payload.attempt()).isEqualTo(1);
            assertThat(payload.snapshotId()).isEqualTo(SNAPSHOT);
            assertThat(payload.documentCount()).isEqualTo(2);
            assertThat(payload.sourcesUsed()).containsExactly("arxiv", "openalex");
            assertThat(payload.unavailableSources()).isEmpty();
            assertThat(payload.partial()).isFalse();
            assertThat(payload.windowFrom()).isEqualTo(LocalDate.of(2019, 1, 1));
            assertThat(payload.windowTo()).isEqualTo(LocalDate.of(2026, 1, 1));
            assertThat(payload.contentHash()).hasSize(64);
        }

        @Test
        void недоступныйИсточникДелаетСнимокНеполным() {
            // BR-C7: результат остаётся пригодным, но обязан быть помечен — отчёт по нему выйдет с
            // оговоркой, а не без неё.
            var partial = CorpusCollected.of(REQUEST, 2, snapshot(List.of("uspto")), AT);
            var payload = (CorpusCollected.Payload) partial.payload();

            assertThat(payload.partial()).isTrue();
            assertThat(payload.unavailableSources()).containsExactly("uspto");
        }
    }

    @Nested
    @DisplayName("Ход сбора")
    class Progressed {

        private final CorpusCollectionProgressed event =
                CorpusCollectionProgressed.of(REQUEST, 1, 45, "Ответили 9 из 12 источников", 9, 12, 310, AT);

        @Test
        void контракт() {
            assertThat(event.eventType()).isEqualTo("horizon.ingestion.CorpusCollectionProgressed");
            assertThat(event.topic()).isEqualTo("horizon.ingestion.events.v1");
        }

        @Test
        void разделТотЖе_чтоИуИсхода() {
            // Ход не должен обгонять «корпус собран» того же запроса.
            var success = CorpusCollected.of(REQUEST, 1, snapshot(List.of()), AT);

            assertThat(event.partitionKey()).isEqualTo(success.partitionKey());
        }

        @Test
        void процентОграничен() {
            assertThat(CorpusCollectionProgressed.of(REQUEST, 1, 140, null, 0, 0, 0, AT).percent())
                    .isEqualTo(100);
        }
    }

    @Nested
    @DisplayName("Сбор не удался")
    class Failed {

        private final CorpusCollectionFailed event = CorpusCollectionFailed.of(
                REQUEST, 3, "NO_DOCUMENTS_FOUND", "по запросу и окну ничего не найдено", false, Map.of(), AT);

        @Test
        void контракт() {
            assertThat(event.eventType()).isEqualTo("horizon.ingestion.CorpusCollectionFailed");
            assertThat(event.topic()).isEqualTo("horizon.ingestion.events.v1");
            assertThat(event.aggregateType()).isEqualTo("CorpusSnapshot");
        }

        @Test
        void разделТотЖе_чтоИуУспеха() {
            // Оба исхода одного запроса обязаны идти одним разделом — иначе порядок между ними не
            // определён, а сага различает их именно по порядку.
            var success = CorpusCollected.of(REQUEST, 3, snapshot(List.of()), AT);

            assertThat(event.partitionKey()).isEqualTo(success.partitionKey());
        }

        @Test
        void нагрузкаНесётПричинуИвозможностьПовтора() {
            var payload = (CorpusCollectionFailed.Payload) event.payload();

            assertThat(payload.researchRequestId()).isEqualTo(REQUEST);
            assertThat(payload.attempt()).isEqualTo(3);
            assertThat(payload.code()).isEqualTo("NO_DOCUMENTS_FOUND");
            assertThat(payload.retryable()).isFalse();
        }

        @Test
        void слишкомДлинноеСообщениеОбрезается() {
            var verbose =
                    CorpusCollectionFailed.of(REQUEST, 1, "CONNECTOR_ERROR", "п".repeat(3000), true, Map.of(), AT);

            assertThat(verbose.message()).hasSize(2000);
        }
    }

    @Test
    void именаТемНеСовпадаютМеждуСобой() {
        // Три темы, а не одна: документы идут потоком в тысячи сообщений, исходы сбора — единицами,
        // и смешивать их значит разделять судьбу самого шумного.
        assertThat(List.of(IngestionTopics.COMMANDS, IngestionTopics.EVENTS, IngestionTopics.DOCUMENTS))
                .doesNotHaveDuplicates()
                .allSatisfy(topic -> assertThat(topic).startsWith("horizon.").endsWith(".v1"));
    }
}
