package dev.horizon.trends.domain.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Разбор режима анализа: две стороны пути, два разных правила.
 *
 * <p>На запросе режим выбирает аналитик, и незнакомое имя — ошибка запроса: попросивший
 * {@code quality} и молча получивший быстрый отчёт узнать об этом не может — отчёт выглядит
 * настоящим.
 *
 * <p>На чтении сохранённого незнакомого или пустого имени бояться нечего: всё записанное до
 * появления режимов считалось в срок быстрого, и уронить из-за этого чтение нельзя.
 */
class AnalysisModeTest {

    @Nested
    @DisplayName("Имя из запроса")
    class FromRequest {

        @Test
        void известноеИмяРазбирается() {
            assertThat(AnalysisMode.of("fast")).isEqualTo(AnalysisMode.FAST);
            assertThat(AnalysisMode.of("quality")).isEqualTo(AnalysisMode.QUALITY);
        }

        @Test
        void отсутствиеИмениОзначаетБыстрый() {
            // Не ошибка: клиент, который о режимах не знает, обязан получать прежнее поведение —
            // ответ в срок ТЗ.
            assertThat(AnalysisMode.of(null)).isEqualTo(AnalysisMode.FAST);
            assertThat(AnalysisMode.of("  ")).isEqualTo(AnalysisMode.FAST);
        }

        @Test
        void незнакомоеИмяОтвергаетсяИНазываетИзвестные() {
            assertThatThrownBy(() -> AnalysisMode.of("slow"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("mode")
                    .hasMessageContaining("slow")
                    .hasMessageContaining("fast")
                    .hasMessageContaining("quality");
        }

        @Test
        void регистрНеМеняетСмысла() {
            assertThat(AnalysisMode.of("QUALITY")).isEqualTo(AnalysisMode.QUALITY);
        }
    }

    @Nested
    @DisplayName("Имя из сохранённого")
    class FromStorage {

        @Test
        void пустоеЗначениеЧитаетсяКакБыстрый() {
            assertThat(AnalysisMode.stored(null)).isEqualTo(AnalysisMode.FAST);
            assertThat(AnalysisMode.stored("")).isEqualTo(AnalysisMode.FAST);
        }

        @Test
        void незнакомоеИмяНеРонетЧтение() {
            assertThat(AnalysisMode.stored("turbo")).isEqualTo(AnalysisMode.FAST);
            assertThat(AnalysisMode.stored("quality")).isEqualTo(AnalysisMode.QUALITY);
        }
    }

    @Test
    void имяНаПроводеНеЗависитОтИмениКонстанты() {
        assertThat(AnalysisMode.FAST.wireName()).isEqualTo("fast");
        assertThat(AnalysisMode.QUALITY.wireName()).isEqualTo("quality");
    }

    @Test
    void умолчаниеЭтоБыстрый() {
        // Срок ТЗ — двадцать минут; качественный режим — согласие ждать дольше, и молча его
        // навязывать нельзя.
        assertThat(AnalysisMode.DEFAULT).isEqualTo(AnalysisMode.FAST);
    }
}
