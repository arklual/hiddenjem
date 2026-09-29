package dev.horizon.ingestion.domain.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Положение инкрементального обхода внутри источника (FR-04.7) и счётчики прогона.
 *
 * <p>Две координаты, потому что источники не согласны, что такое курсор: OpenAlex и Crossref выдают
 * непрозрачный токен, arXiv и GitHub считают смещением, а у RSS курсора нет вовсе — там водораздел
 * держится по дате последней публикации.
 *
 * <p>Главное свойство водораздела — он движется только вперёд. Источник, вернувший запись постарше
 * (а так делают почти все ленты), не имеет права отмотать нас назад: следующий прогон перечитал бы
 * историю целиком.
 */
class CursorTest {

    private static final LocalDate MARCH = LocalDate.of(2026, 3, 1);
    private static final LocalDate APRIL = LocalDate.of(2026, 4, 1);

    @Nested
    @DisplayName("Начало")
    class Start {

        @Test
        void началоЭтоНиТокенаНиДаты() {
            assertThat(Cursor.start().isStart()).isTrue();
            assertThat(Cursor.start().valueOrEmpty()).isEmpty();
            assertThat(Cursor.start().lastPublishedOnOrEmpty()).isEmpty();
        }

        @Test
        void пустойТокенРавносиленЕгоОтсутствию() {
            // Источник, вернувший пустую строку, не сообщил ничего — и не должен выглядеть так,
            // будто сообщил.
            assertThat(new Cursor("   ", null).isStart()).isTrue();
            assertThat(new Cursor("", null).value()).isNull();
        }

        @Test
        void пробелыВокругТокенаСнимаются() {
            assertThat(new Cursor("  page-2  ", null).value()).isEqualTo("page-2");
        }

        @Test
        void курсорСДатойНеНачальный() {
            assertThat(Cursor.ofDate(MARCH).isStart()).isFalse();
        }
    }

    @Nested
    @DisplayName("Водораздел движется только вперёд")
    class Watermark {

        @Test
        void перваяДатаПринимается() {
            assertThat(Cursor.start().withWatermark(MARCH).lastPublishedOn()).isEqualTo(MARCH);
        }

        @Test
        void болееПозняяДатаСдвигаетВодораздел() {
            assertThat(Cursor.ofDate(MARCH).withWatermark(APRIL).lastPublishedOn())
                    .isEqualTo(APRIL);
        }

        @Test
        void болееРанняяДатаНеОтматываетНазад() {
            assertThat(Cursor.ofDate(APRIL).withWatermark(MARCH).lastPublishedOn())
                    .isEqualTo(APRIL);
        }

        @Test
        void таЖеДатаНичегоНеМеняет() {
            assertThat(Cursor.ofDate(MARCH).withWatermark(MARCH).lastPublishedOn())
                    .isEqualTo(MARCH);
        }

        @Test
        void отсутствиеДатыНичегоНеМеняет() {
            var cursor = Cursor.ofDate(MARCH);

            assertThat(cursor.withWatermark(null)).isSameAs(cursor);
        }

        @Test
        void токенПриСдвигеВодоразделаСохраняется() {
            var cursor = new Cursor("page-7", MARCH);

            assertThat(cursor.withWatermark(APRIL).value()).isEqualTo("page-7");
        }

        @Test
        void сменаТокенаСохраняетВодораздел() {
            assertThat(new Cursor("page-7", MARCH).withValue("page-8")).isEqualTo(new Cursor("page-8", MARCH));
        }
    }

    @Nested
    @DisplayName("Счётчики")
    class Counters {

        @Test
        void сложениеПоэлементно() {
            var sum = new RunCounters(10, 6, 3, 1).plus(new RunCounters(5, 5, 0, 0));

            assertThat(sum).isEqualTo(new RunCounters(15, 11, 3, 1));
        }

        @Test
        void нулевойПрогонВиденКакПустой() {
            assertThat(RunCounters.ZERO.isEmpty()).isTrue();
            assertThat(new RunCounters(0, 0, 0, 1).isEmpty()).isFalse();
        }

        @Test
        void отрицательныеЗначенияНедопустимы() {
            assertThatThrownBy(() -> new RunCounters(-1, 0, 0, 0)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> new RunCounters(0, -1, 0, 0)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> new RunCounters(0, 0, -1, 0)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> new RunCounters(0, 0, 0, -1)).isInstanceOf(RuntimeException.class);
        }

        @Test
        void расхождениеИтоговНеОшибка() {
            // fetched заведомо больше суммы остальных: часть записей отсеивается до нормализации —
            // вне окна, не тот класс источника. Превращать это в исключение значило бы прерывать
            // хороший прогон из-за бухгалтерии.
            assertThat(new RunCounters(100, 1, 1, 1)).isNotNull();
        }
    }
}
