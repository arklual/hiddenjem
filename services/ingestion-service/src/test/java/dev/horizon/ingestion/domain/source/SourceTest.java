package dev.horizon.ingestion.domain.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import dev.horizon.ingestion.domain.document.SourceClass;

/**
 * Настроенный источник — корень агрегата, владеющий политикой одного коннектора.
 *
 * <p>Код коннектора развёрнут, а политика — данные: исследователь может придушить или отключить
 * source, ведущий себя плохо, не дожидаясь выпуска (UC-11). Отсюда и требования к этому классу:
 * менять можно только то, что политика, и только в границах, за которыми начинается вред.
 *
 * <p>Отдельно проверяется различие «выключен» и «недоступен». Источник, которому нужен ключ, а ключа
 * нет, — недоступен, а не сломан: прогон сообщает о нём в `unavailableSources` и продолжается
 * (BR-C7). Спутать эти два состояния значит либо потерять источник молча, либо отменить сбор целиком.
 */
class SourceTest {

    private static Source source(boolean enabled, boolean requiresApiKey) {
        return Source.of(
                "openalex",
                "OpenAlex",
                SourceClass.JOURNAL_ARTICLE,
                enabled,
                "https://api.openalex.org",
                60,
                requiresApiKey,
                Map.of("mailto", "horizon@example.org"),
                0.9,
                1L);
    }

    @Nested
    @DisplayName("Пригодность")
    class Usability {

        @Test
        void включённыйБезКлючаПригоден() {
            assertThat(source(true, false).isUsable(false)).isTrue();
        }

        @Test
        void выключенныйНеПригоденДажеСключом() {
            assertThat(source(false, false).isUsable(true)).isFalse();
        }

        @Test
        void требующийКлючаБезКлючаНеПригоден() {
            // Именно недоступен, а не сломан: прогон обязан продолжиться без него.
            assertThat(source(true, true).isUsable(false)).isFalse();
        }

        @Test
        void требующийКлючаСключомПригоден() {
            assertThat(source(true, true).isUsable(true)).isTrue();
        }
    }

    @Nested
    @DisplayName("Переключение")
    class Toggling {

        @Test
        void включитьИвыключить() {
            var source = source(false, false);

            source.enable();
            assertThat(source.isEnabled()).isTrue();

            source.disable();
            assertThat(source.isEnabled()).isFalse();
        }

        @Test
        void отсутствующееЗначениеНичегоНеМеняет() {
            // Частичное обновление: не переданное поле означает «не трогать», а не «выключить».
            var source = source(true, false);

            source.changeEnabled(null);

            assertThat(source.isEnabled()).isTrue();
        }

        @Test
        void переданноеЗначениеПрименяется() {
            var source = source(true, false);

            source.changeEnabled(false);

            assertThat(source.isEnabled()).isFalse();
        }
    }

    @Nested
    @DisplayName("Ограничение частоты")
    class RateLimit {

        @Test
        void значениеМеняется() {
            var source = source(true, false);

            source.changeRateLimit(120);

            assertThat(source.rateLimitPerMinute()).isEqualTo(120);
        }

        @Test
        void отсутствующееЗначениеНичегоНеМеняет() {
            var source = source(true, false);

            source.changeRateLimit(null);

            assertThat(source.rateLimitPerMinute()).isEqualTo(60);
        }

        @Test
        void нольНедопустим() {
            // Нулевой предел — это выключенный источник, выраженный так, что об этом никто не
            // догадается: он остаётся «включённым» и просто перестаёт отдавать документы.
            var source = source(true, false);

            assertThatThrownBy(() -> source.changeRateLimit(0)).isInstanceOf(RuntimeException.class);
            assertThat(source.rateLimitPerMinute()).isEqualTo(60);
        }

        @Test
        void границыДопустимы() {
            var source = source(true, false);

            source.changeRateLimit(Source.MIN_RATE_LIMIT_PER_MINUTE);
            assertThat(source.rateLimitPerMinute()).isEqualTo(Source.MIN_RATE_LIMIT_PER_MINUTE);

            source.changeRateLimit(Source.MAX_RATE_LIMIT_PER_MINUTE);
            assertThat(source.rateLimitPerMinute()).isEqualTo(Source.MAX_RATE_LIMIT_PER_MINUTE);
        }

        @Test
        void вышеПотолкаНедопустимо() {
            var source = source(true, false);

            assertThatThrownBy(() -> source.changeRateLimit(Source.MAX_RATE_LIMIT_PER_MINUTE + 1))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Nested
    @DisplayName("Охранные условия")
    class Guards {

        @Test
        void идентификаторОбязателенИограниченПоДлине() {
            assertThatThrownBy(() -> Source.of(
                            " ", "n", SourceClass.JOURNAL_ARTICLE, true, "https://x", 10, false, Map.of(), 0.5, 0L))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> Source.of(
                            "i".repeat(49),
                            "n",
                            SourceClass.JOURNAL_ARTICLE,
                            true,
                            "https://x",
                            10,
                            false,
                            Map.of(),
                            0.5,
                            0L))
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        void весАвторитетностиЛежитОтНуляДоЕдиницы() {
            // Это априорная оценка качества свидетельства, и она сравнивается с другими: значение
            // вне отрезка сделало бы сравнение бессмысленным.
            assertThatThrownBy(() -> Source.of(
                            "s", "n", SourceClass.JOURNAL_ARTICLE, true, "https://x", 10, false, Map.of(), 1.5, 0L))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() -> Source.of(
                            "s", "n", SourceClass.JOURNAL_ARTICLE, true, "https://x", 10, false, Map.of(), -0.1, 0L))
                    .isInstanceOf(RuntimeException.class);
        }

        @Test
        void адресИназваниеОбязательны() {
            assertThatThrownBy(() -> Source.of(
                            "s", " ", SourceClass.JOURNAL_ARTICLE, true, "https://x", 10, false, Map.of(), 0.5, 0L))
                    .isInstanceOf(RuntimeException.class);
            assertThatThrownBy(() ->
                            Source.of("s", "n", SourceClass.JOURNAL_ARTICLE, true, " ", 10, false, Map.of(), 0.5, 0L))
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Nested
    @DisplayName("Настройки и тождество")
    class ConfigAndIdentity {

        @Test
        void настройкиКопируютсяИНеизменяемы() {
            var mutable = new HashMap<String, Object>();
            mutable.put("mailto", "a@b.c");
            var source =
                    Source.of("s", "n", SourceClass.JOURNAL_ARTICLE, true, "https://x", 10, false, mutable, 0.5, 0L);

            mutable.put("mailto", "подменено");

            assertThat(source.config()).containsExactly(Map.entry("mailto", "a@b.c"));
            assertThatThrownBy(() -> source.config().put("k", "v")).isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void отсутствующиеНастройкиЭтоПустаяКарта() {
            var source = Source.of("s", "n", SourceClass.JOURNAL_ARTICLE, true, "https://x", 10, false, null, 0.5, 0L);

            assertThat(source.config()).isEmpty();
        }

        @Test
        void источникиРавныПоИдентификатору() {
            assertThat(source(true, false)).isEqualTo(source(false, true)).isNotEqualTo(null);
            assertThat(source(true, false)).hasSameHashCodeAs(source(false, true));
        }

        @Test
        void вСтроковомПредставленииВидноСостояние() {
            assertThat(source(true, false).toString())
                    .contains("openalex")
                    .contains("enabled=true")
                    .contains("60/min");
        }
    }
}
