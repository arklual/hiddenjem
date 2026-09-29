package dev.horizon.trends.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import dev.horizon.trends.config.FeatureFlag;

/**
 * A switched-off feature is gone, not merely unpainted (BR-A79…BR-A81).
 *
 * <p>Реестр фич заведён как страховка на случай, когда функцию нужно снять без пересборки. До сих
 * пор это обещание не исполнял ни один тест: контроллеры обоих сервисов не проходил никто, и
 * забытый вызов гейта выглядел бы в коде как отсутствие строки, которую никто не искал. Узнали бы о
 * нём в тот единственный момент, ради которого переключатель существует.
 *
 * <p>Флаги задаются свойствами, как в бою, а {@code FeatureGate} — настоящий: подменить его
 * заглушкой значило бы проверять заглушку, тогда как дефект живёт ровно в том, дошёл ли вызов до
 * гейта.
 */
@TestPropertySource(
        properties = {
            "horizon.features.radar=false",
            "horizon.features.direction-portrait=false",
            "horizon.features.report-delta=false",
            "horizon.features.term-trace=false",
            "horizon.features.report-export=false",
            "horizon.features.topic-search=false",
            "horizon.features.trend-feedback=false",
            "horizon.features.direction-overlap=false",
            "horizon.features.saved-domains=false"
        })
class FeatureGateWebTest extends WebSliceTestBase {

    /**
     * Флаги, у которых нет своего эндпоинта: их содержимое рисуется из уже полученных данных.
     *
     * <p>Названы поимённо (P4), а не отфильтрованы по признаку: «выключил, а сервер отвечает» — это
     * либо решение, либо дефект, и отличить их можно только по тому, записано ли решение.
     */
    // Пусто с тех пор, как карта портфеля ушла из интерфейса вместе со своим флагом.
    private static final Set<FeatureFlag> CLIENT_SIDE_ONLY = Set.of();

    /**
     * Флаги, которые вырезают данные из ответа вместо отказа: эндпоинт остаётся, содержимое исчезает.
     *
     * <p>Их проверяют свои тесты — здесь важно, что они не молчаливое исключение из таблицы.
     */
    private static final Set<FeatureFlag> TRIMS_THE_ANSWER = Set.of(FeatureFlag.DIRECTION_PORTRAIT);

    @Autowired
    private MockMvc mvc;

    @ParameterizedTest(name = "{0} → {1}")
    @MethodSource("dev.horizon.trends.adapter.web.GatedEndpoints#all")
    void aSwitchedOffFeatureAnswersNotFound(FeatureFlag flag, Supplier<MockHttpServletRequestBuilder> request)
            throws Exception {
        mvc.perform(withAuth(request.get()))
                // 404, а не 403: право ни при чём, функции нет ни у кого. И не пустое тело — пустота
                // читается как «данных нет», то есть как утверждение о предмете, а не о сборке.
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString(flag.key())));
    }

    @Test
    void everyFlagInTheRegistryIsAccountedFor() {
        // P6. Реестр растёт, и проверка, которую нужно не забыть дополнить, — это проверка, которую
        // забудут. Новый флаг без записи роняет сборку здесь, а не обнаруживается на демонстрации.
        var covered = GatedEndpoints.all()
                .map(arguments -> (FeatureFlag) arguments.get()[0])
                .collect(java.util.stream.Collectors.toSet());

        List<FeatureFlag> unaccounted = Arrays.stream(FeatureFlag.values())
                .filter(flag -> !covered.contains(flag))
                .filter(flag -> !CLIENT_SIDE_ONLY.contains(flag))
                .filter(flag -> !TRIMS_THE_ANSWER.contains(flag))
                .toList();

        assertThat(unaccounted)
                .as("эти флаги не проверены и не названы клиентскими — переключатель у них ничем не подтверждён")
                .isEmpty();
    }
}
