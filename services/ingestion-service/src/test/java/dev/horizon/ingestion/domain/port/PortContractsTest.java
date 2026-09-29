package dev.horizon.ingestion.domain.port;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.run.Cursor;

/**
 * Порты доменного слоя: самоописание коннектора, страница результатов и пустой поток документов.
 *
 * <p>Самое содержательное здесь — «недоступен» вместо «сломан». Коннектор, которому нужен ключ, а
 * ключа нет, сообщает о себе причину недоступности, сбор его пропускает и перечисляет в
 * `unavailableSources`, а отчёт честно помечается неполным (BR-C7). Не будь этого различия, один
 * ненастроенный источник отменял бы весь анализ.
 */
class PortContractsTest {

    @Nested
    @DisplayName("Самоописание коннектора")
    class Descriptor {

        @Test
        void доступныйПоУмолчанию() {
            var descriptor = SourceDescriptor.available("arxiv", "arXiv", SourceClass.PREPRINT, 30);

            assertThat(descriptor.available()).isTrue();
            assertThat(descriptor.unavailableReasonOrEmpty()).isEmpty();
            assertThat(descriptor.requiresApiKey()).isFalse();
            assertThat(descriptor.credentialsConfigured()).isTrue();
        }

        @Test
        void недоступностьНесётПричину() {
            var descriptor = SourceDescriptor.available("alphaxiv", "alphaXiv", SourceClass.PREPRINT, 30)
                    .unavailable("не настроен ключ API");

            assertThat(descriptor.available()).isFalse();
            assertThat(descriptor.unavailableReasonOrEmpty()).contains("не настроен ключ API");
        }

        @Test
        void недоступностьНеТеряетОстальногоОписания() {
            var descriptor = SourceDescriptor.available("alphaxiv", "alphaXiv", SourceClass.PREPRINT, 30)
                    .unavailable("нет ключа");

            assertThat(descriptor.id()).isEqualTo("alphaxiv");
            assertThat(descriptor.displayName()).isEqualTo("alphaXiv");
            assertThat(descriptor.primaryClass()).isEqualTo(SourceClass.PREPRINT);
            assertThat(descriptor.requestsPerMinute()).isEqualTo(30);
        }

        @Test
        void пустаяПричинаОзначаетДоступность() {
            // Пустая строка — это «причины нет». Иначе источник выглядел бы недоступным без объяснения.
            var descriptor = SourceDescriptor.available("arxiv", "arXiv", SourceClass.PREPRINT, 30)
                    .unavailable("   ");

            assertThat(descriptor.available()).isTrue();
        }

        @Test
        void причинаОчищаетсяОтПробелов() {
            var descriptor = SourceDescriptor.available("arxiv", "arXiv", SourceClass.PREPRINT, 30)
                    .unavailable("  нет ключа  ");

            assertThat(descriptor.unavailableReason()).isEqualTo("нет ключа");
        }

        @Test
        void безСпискаКлассовОстаётсяОдинОсновной() {
            var descriptor = new SourceDescriptor("s", "S", SourceClass.NEWS, null, 5, false, true, null, false);

            assertThat(descriptor.providedClasses()).containsExactly(SourceClass.NEWS);
        }

        @Test
        void смешанныйКорпусПеречисляетНесколькоКлассов() {
            // Эталонный корпус отдаёт разное — и обязан это объявлять, иначе отбор по классу
            // источника молча его пропустит.
            var descriptor = new SourceDescriptor(
                    "fixture",
                    "Эталонный корпус",
                    SourceClass.JOURNAL_ARTICLE,
                    Set.of(SourceClass.JOURNAL_ARTICLE, SourceClass.PREPRINT, SourceClass.PATENT),
                    100,
                    false,
                    true,
                    null,
                    false);

            assertThat(descriptor.providedClasses())
                    .containsExactlyInAnyOrder(SourceClass.JOURNAL_ARTICLE, SourceClass.PREPRINT, SourceClass.PATENT);
        }

        @Test
        void списокКлассовНеизменяем() {
            var descriptor = SourceDescriptor.available("arxiv", "arXiv", SourceClass.PREPRINT, 30);

            assertThatThrownBy(() -> descriptor.providedClasses().add(SourceClass.NEWS))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void сменаЧастотыНеТрогаетОстальное() {
            var descriptor = SourceDescriptor.available("arxiv", "arXiv", SourceClass.PREPRINT, 30)
                    .withRequestsPerMinute(120);

            assertThat(descriptor.requestsPerMinute()).isEqualTo(120);
            assertThat(descriptor.id()).isEqualTo("arxiv");
            assertThat(descriptor.available()).isTrue();
        }

        @Test
        void нулеваяЧастотаНедопустима() {
            assertThatThrownBy(() -> SourceDescriptor.available("arxiv", "arXiv", SourceClass.PREPRINT, 0))
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        void идентификаторИназваниеОбязательны() {
            assertThatThrownBy(() -> SourceDescriptor.available(" ", "arXiv", SourceClass.PREPRINT, 10))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> SourceDescriptor.available("arxiv", " ", SourceClass.PREPRINT, 10))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Nested
    @DisplayName("Страница результатов")
    class Page {

        @Test
        void числоСтраницОкругляетсяВверх() {
            // Одиннадцатый элемент — это вторая страница, а не «десять с хвостиком».
            assertThat(new PageResult<>(List.of("a"), 0, 10, 11).totalPages()).isEqualTo(2);
            assertThat(new PageResult<>(List.of("a"), 0, 10, 10).totalPages()).isEqualTo(1);
            assertThat(new PageResult<>(List.of("a"), 0, 10, 0).totalPages()).isZero();
        }

        @Test
        void нулевойРазмерНеДелитНаНоль() {
            assertThat(new PageResult<>(List.of(), 0, 0, 5).totalPages()).isZero();
        }

        @Test
        void пустаяСтраницаСохраняетКоординаты() {
            var empty = PageResult.empty(3, 20);

            assertThat(empty.content()).isEmpty();
            assertThat(empty.page()).isEqualTo(3);
            assertThat(empty.size()).isEqualTo(20);
            assertThat(empty.totalElements()).isZero();
        }

        @Test
        void преобразованиеСохраняетРазбиение() {
            var mapped = new PageResult<>(List.of(1, 2, 3), 2, 3, 30).map(Object::toString);

            assertThat(mapped.content()).containsExactly("1", "2", "3");
            assertThat(mapped.page()).isEqualTo(2);
            assertThat(mapped.size()).isEqualTo(3);
            assertThat(mapped.totalElements()).isEqualTo(30);
        }

        @Test
        void содержимоеКопируетсяИнеизменяемо() {
            var page = new PageResult<>(List.of("a"), 0, 10, 1);

            assertThatThrownBy(() -> page.content().add("b")).isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Nested
    @DisplayName("Пустой поток документов")
    class EmptyStream {

        @Test
        void ничегоНеОтдаётИничегоНеСчитает() {
            try (var stream = DocumentStream.empty(Cursor.ofValue("page-3"))) {
                assertThat(stream.documents()).isEmpty();
                assertThat(stream.fetched()).isZero();
                assertThat(stream.rejected()).isZero();
                assertThat(stream.cursor()).isEqualTo(Cursor.ofValue("page-3"));
            }
        }

        @Test
        void безКурсораВозвращаетНачало() {
            // Отказавший коннектор не имеет права сообщить «курсора нет» так, чтобы это выглядело
            // как «дочитано до конца».
            try (var stream = DocumentStream.empty(null)) {
                assertThat(stream.cursor()).isEqualTo(Cursor.start());
            }
        }
    }

    @Nested
    @DisplayName("Выключен настройкой — не то же, что недоступен")
    class SwitchedOff {

        @Test
        void выключенныйНеУчаствуетИНеСчитаетсяПробелом() {
            var descriptor = SourceDescriptor.available("arxiv", "arXiv", SourceClass.PREPRINT, 30)
                    .switchedOff("Коннектор arxiv выключен конфигурацией");

            assertThat(descriptor.switchedOff()).isTrue();
            // Причина сохраняется: она нужна оператору в журнале, а не аналитику в отчёте.
            assertThat(descriptor.unavailableReasonOrEmpty()).isPresent();
        }

        @Test
        void недоступныйНеСчитаетсяВыключенным() {
            // Разница не косметическая. «Недоступен» — пробел в покрытии, о котором аналитику надо
            // сказать: источник должен был участвовать и не смог. «Выключен» — решение оператора, и
            // предупреждать о нём в каждом отчёте значит приучить не читать предупреждения.
            var descriptor = SourceDescriptor.available("alphaxiv", "alphaXiv", SourceClass.PREPRINT, 30)
                    .unavailable("Не задан HORIZON_ALPHAXIV_API_KEY");

            assertThat(descriptor.switchedOff()).isFalse();
            assertThat(descriptor.available()).isFalse();
        }

        @Test
        void доступныйПоУмолчаниюНеВыключен() {
            assertThat(SourceDescriptor.available("arxiv", "arXiv", SourceClass.PREPRINT, 30)
                            .switchedOff())
                    .isFalse();
        }

        @Test
        void сменаЧастотыНеВключаетВыключенный() {
            var descriptor = SourceDescriptor.available("rss", "RSS", SourceClass.NEWS, 10)
                    .switchedOff("выключен")
                    .withRequestsPerMinute(60);

            assertThat(descriptor.switchedOff()).isTrue();
        }
    }
}
