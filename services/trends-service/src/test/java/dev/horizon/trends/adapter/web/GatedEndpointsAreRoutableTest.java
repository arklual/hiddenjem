package dev.horizon.trends.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Supplier;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import dev.horizon.trends.config.FeatureFlag;

/**
 * Каждая строка таблицы ведёт в контроллер, а не в пустоту (P5).
 *
 * <p>Без этой проверки соседний тест зеленел бы и на неверном пути: 404 «такого маршрута нет»
 * неотличим по коду от 404 «функция выключена», и утверждение «гейт сработал» держалось бы на
 * опечатке. Первая версия таблицы содержала две такие строки — радар искали по несуществующему
 * адресу, а параметр трассировки термина назывался иначе.
 *
 * <p>Флаги здесь по умолчанию включены. Что ответит сценарий — неважно и не проверяется: важно, что
 * запрос дошёл до обработчика.
 */
class GatedEndpointsAreRoutableTest extends WebSliceTestBase {

    @Autowired
    private MockMvc mvc;

    @ParameterizedTest(name = "{0} → {1}")
    @MethodSource("dev.horizon.trends.adapter.web.GatedEndpoints#all")
    void eachGatedRequestReachesItsController(FeatureFlag flag, Supplier<MockHttpServletRequestBuilder> request)
            throws Exception {
        var result = mvc.perform(WebSliceTestBase.withAuth(request.get())).andReturn();

        assertThat(result.getHandler())
                .as("запрос для %s не дошёл ни до одного обработчика — путь в таблице неверен", flag.key())
                .isNotNull();
    }
}
