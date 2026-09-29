package dev.horizon.ingestion.domain.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Прогон сбора — корень агрегата (модель предметной области, §5).
 *
 * <p>Здесь держится инвариант, цена ошибки в котором — молча потерянные документы: <b>курсор
 * двигается только по зафиксированной странице</b>. Сдвинуть его раньше — значит при падении
 * пропустить всё, что было между; не двигать вовсе — перечитывать источник целиком каждый раз.
 * Единственный, кто пишет `cursorAfter`, — {@link IngestionRun#commitPage}, и вызывается он
 * приложением только после того, как документы страницы и строки outbox зафиксированы.
 *
 * <p>Второй инвариант — машина состояний: RUNNING → COMPLETED | PARTIAL | FAILED, конечные состояния
 * окончательны. PARTIAL здесь полноправный исход, а не ошибка: прогон, собравший документы и
 * упёршийся в предохранитель на последних страницах, полезен — отчёт по нему помечается неполным, а
 * не выбрасывается (BR-C7).
 *
 * <p>Мутационный прогон показал по пакету 1%: не проверялось ничего из перечисленного.
 */
class IngestionRunTest {

    private static final Instant STARTED = Instant.parse("2026-08-15T10:00:00Z");
    private static final Instant FINISHED = Instant.parse("2026-08-15T10:05:00Z");

    private static IngestionRun running() {
        return IngestionRun.start(
                UUID.randomUUID(),
                "openalex",
                RunMode.INCREMENTAL,
                null,
                "quantum sensing",
                LocalDate.of(2019, 1, 1),
                LocalDate.of(2026, 1, 1),
                Cursor.start(),
                STARTED,
                "trace-1");
    }

    @Nested
    @DisplayName("Курсор")
    class CursorAdvance {

        @Test
        void доПервойЗафиксированнойСтраницыКурсораНет() {
            assertThat(running().cursorAfter()).isEmpty();
        }

        @Test
        void фиксацияСтраницыДвигаетКурсор() {
            var run = running();

            run.commitPage(Cursor.ofValue("page-2"), new RunCounters(10, 8, 2, 0));

            assertThat(run.cursorAfter()).contains(Cursor.ofValue("page-2"));
        }

        @Test
        void источникБезКурсораЕгоНеСбрасывает() {
            // RSS не умеет курсора и сообщает null. Это не повод забыть, где мы остановились.
            var run = running();
            run.commitPage(Cursor.ofValue("page-2"), new RunCounters(10, 10, 0, 0));

            run.commitPage(null, new RunCounters(5, 5, 0, 0));

            assertThat(run.cursorAfter()).contains(Cursor.ofValue("page-2"));
        }

        @Test
        void начальныйКурсорНеЗатираетДостигнутый() {
            var run = running();
            run.commitPage(Cursor.ofValue("page-2"), RunCounters.ZERO);

            run.commitPage(Cursor.start(), RunCounters.ZERO);

            assertThat(run.cursorAfter()).contains(Cursor.ofValue("page-2"));
        }

        @Test
        void счётчикиСкладываютсяПоСтраницам() {
            var run = running();

            run.commitPage(Cursor.ofValue("p1"), new RunCounters(10, 7, 3, 0));
            run.commitPage(Cursor.ofValue("p2"), new RunCounters(5, 4, 1, 0));

            assertThat(run.counters()).isEqualTo(new RunCounters(15, 11, 4, 0));
        }

        @Test
        void отброшенныеУчитываютсяОтдельно() {
            var run = running();

            run.recordRejected(3);

            assertThat(run.counters().rejected()).isEqualTo(3);
        }

        @Test
        void отрицательноеЧислоОтброшенныхНеУменьшаетСчётчик() {
            var run = running();
            run.recordRejected(5);

            run.recordRejected(-10);

            assertThat(run.counters().rejected()).isEqualTo(5);
        }
    }

    @Nested
    @DisplayName("Состояния")
    class Lifecycle {

        @Test
        void прогонНачинаетсяИдущим() {
            var run = running();

            assertThat(run.status()).isEqualTo(RunStatus.RUNNING);
            assertThat(run.status().isTerminal()).isFalse();
            assertThat(run.finishedAt()).isNull();
        }

        @Test
        void успешноеЗавершение() {
            var run = running();

            run.complete(FINISHED);

            assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
            assertThat(run.finishedAt()).isEqualTo(FINISHED);
        }

        @Test
        void частичноеЗавершениеНесётПричину() {
            var run = running();

            run.completePartially("RATE_LIMITED", "источник ответил 429 на последних страницах", FINISHED);

            assertThat(run.status()).isEqualTo(RunStatus.PARTIAL);
            assertThat(run.errorCode()).isEqualTo("RATE_LIMITED");
            assertThat(run.errorMessage()).contains("429");
        }

        @Test
        void отказНесётПричину() {
            var run = running();

            run.fail("CONNECTOR_ERROR", "источник недоступен", FINISHED);

            assertThat(run.status()).isEqualTo(RunStatus.FAILED);
            assertThat(run.errorCode()).isEqualTo("CONNECTOR_ERROR");
        }

        @Test
        void длинныйКодОшибкиОбрезается() {
            var run = running();

            run.fail("E".repeat(60), "сообщение", FINISHED);

            assertThat(run.errorCode()).hasSize(48);
        }

        @Test
        void конечноеСостояниеОкончательно() {
            var run = running();
            run.complete(FINISHED);

            assertThatThrownBy(() -> run.fail("X", "поздно", FINISHED)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> run.complete(FINISHED)).isInstanceOf(RuntimeException.class);
        }

        @Test
        void завершённыйПрогонНеПринимаетСтраниц() {
            // Иначе курсор сдвинулся бы после того, как прогон уже отчитался, — и следующий прогон
            // начал бы не с того места, о котором отчитались.
            var run = running();
            run.complete(FINISHED);

            assertThatThrownBy(() -> run.commitPage(Cursor.ofValue("p9"), RunCounters.ZERO))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> run.recordRejected(1)).isInstanceOf(RuntimeException.class);
        }

        @Test
        void всеИсходыКромеИдущегоКонечны() {
            assertThat(RunStatus.COMPLETED.isTerminal()).isTrue();
            assertThat(RunStatus.PARTIAL.isTerminal()).isTrue();
            assertThat(RunStatus.FAILED.isTerminal()).isTrue();
            assertThat(RunStatus.RUNNING.isTerminal()).isFalse();
        }

        @Test
        void вернутьсяВИдущееНельзяНиоткуда() {
            for (RunStatus status : RunStatus.values()) {
                assertThat(status.canTransitionTo(RunStatus.RUNNING)).isFalse();
                assertThat(status.canTransitionTo(null)).isFalse();
            }
        }

        @Test
        void изКонечногоНикудаНельзя() {
            assertThat(RunStatus.COMPLETED.canTransitionTo(RunStatus.FAILED)).isFalse();
            assertThat(RunStatus.PARTIAL.canTransitionTo(RunStatus.COMPLETED)).isFalse();
            assertThat(RunStatus.FAILED.canTransitionTo(RunStatus.PARTIAL)).isFalse();
        }
    }

    @Nested
    @DisplayName("Охранные условия и тождество")
    class Invariants {

        @Test
        void окноНеМожетБытьВывернуто() {
            assertThatThrownBy(() -> IngestionRun.start(
                            UUID.randomUUID(),
                            "openalex",
                            RunMode.BACKFILL,
                            null,
                            "q",
                            LocalDate.of(2026, 1, 1),
                            LocalDate.of(2019, 1, 1),
                            null,
                            STARTED,
                            null))
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        void обязательныеПоляПроверяются() {
            assertThatThrownBy(() -> IngestionRun.start(
                            null, "openalex", RunMode.BACKFILL, null, "q", null, null, null, STARTED, null))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> IngestionRun.start(
                            UUID.randomUUID(), " ", RunMode.BACKFILL, null, "q", null, null, null, STARTED, null))
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        void отсутствующийКурсорСтановитсяНачальным() {
            var run = IngestionRun.start(
                    UUID.randomUUID(), "rss", RunMode.INCREMENTAL, null, "q", null, null, null, STARTED, null);

            assertThat(run.cursorBefore()).isEqualTo(Cursor.start());
            assertThat(run.counters()).isEqualTo(RunCounters.ZERO);
        }

        @Test
        void прогоныРавныПоИдентификатору() {
            var id = UUID.randomUUID();
            var one = IngestionRun.start(id, "openalex", RunMode.BACKFILL, null, "q", null, null, null, STARTED, null);
            var other =
                    IngestionRun.start(id, "arxiv", RunMode.INCREMENTAL, null, "z", null, null, null, FINISHED, null);

            assertThat(one).isEqualTo(other).hasSameHashCodeAs(other);
            assertThat(one).isNotEqualTo(running()).isNotEqualTo(null);
        }

        @Test
        void вСтроковомПредставленииЕстьЧтоИскатьВЖурнале() {
            assertThat(running().toString())
                    .contains("openalex")
                    .contains("INCREMENTAL")
                    .contains("RUNNING");
        }
    }
}
