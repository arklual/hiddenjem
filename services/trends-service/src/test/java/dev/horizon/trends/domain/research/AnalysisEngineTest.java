package dev.horizon.trends.domain.research;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Движок после вывода методологии из продукта: считает один, подписей две.
 *
 * <p>Выбора больше нет, поэтому и разбирать из запроса нечего. Остаётся подпись готовых отчётов, и
 * ей нельзя переехать на новый движок вслед за расчётом: всё, что выпущено раньше, посчитано
 * методологией, и отчёт неизменяем.
 */
class AnalysisEngineTest {

    @Test
    void новыеОтчётыСчитаетСигнальныйДвижок() {
        assertThat(AnalysisEngine.CURRENT).isEqualTo(AnalysisEngine.SIGNALS);
    }

    @Test
    void отчётБезПодписиПосчитанМетодологией() {
        // Утверждение о прошлом, а не запасной путь: подписи нет только у отчётов, выпущенных до
        // появления второго движка, и с выводом методологии оно не меняется.
        assertThat(AnalysisEngine.UNLABELLED_REPORT).isEqualTo(AnalysisEngine.METHODOLOGY);
    }

    @Test
    void имяНаПроводеНеЗависитОтИмениКонстанты() {
        // Контракт задан схемами (`analyze-domain.command.json`) и движком; переименование
        // константы Java не должно его менять — поэтому имя хранится явно.
        assertThat(AnalysisEngine.METHODOLOGY.wireName()).isEqualTo("methodology");
        assertThat(AnalysisEngine.SIGNALS.wireName()).isEqualTo("signals");
    }
}
