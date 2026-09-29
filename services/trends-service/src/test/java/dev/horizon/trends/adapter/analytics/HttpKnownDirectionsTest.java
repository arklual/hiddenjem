package dev.horizon.trends.adapter.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Подсказка «что можно спросить» не должна становиться условием работы экрана.
 *
 * <p>Список направлений приходит из движка синхронно, потому что человек смотрит на экран. Значит
 * появляется зависимость там, где её раньше не было: экран поиска — первое, что открывает аналитик,
 * и уронить его из-за недоступной подсказки значит обменять работающий продукт на удобство.
 */
class HttpKnownDirectionsTest {

    private static final String BASE = "http://analytics:8000";

    private MockRestServiceServer server;
    private HttpKnownDirections directions;

    private void wire() {
        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();
        directions = new HttpKnownDirections(builder.build(), "секрет");
    }

    @Test
    @DisplayName("список приходит от движка вместе с внутренним токеном")
    void thelistComesFromTheEngine() {
        wire();
        server.expect(requestTo(BASE + "/internal/directions"))
                .andExpect(header("X-Internal-Token", "секрет"))
                .andRespond(withSuccess(
                        "{\"directions\":[\"квантовые вычисления\",\"финтех\"]}", MediaType.APPLICATION_JSON));

        assertThat(directions.list()).containsExactly("квантовые вычисления", "финтех");
        server.verify();
    }

    @Test
    @DisplayName("недоступный движок оставляет экран рабочим")
    void anunavailableEngineLeavesTheScreenUsable() {
        // Аналитик, у которого не показали подсказку, вводит направление сам — как делал раньше.
        // Аналитик, у которого не открылся экран, не делает ничего.
        wire();
        server.expect(requestTo(BASE + "/internal/directions")).andRespond(withServerError());

        assertThat(directions.list()).isEmpty();
    }

    @Test
    @DisplayName("пустой ответ — это пустой список, а не отказ")
    void anemptyBodyIsAnEmptyList() {
        wire();
        server.expect(requestTo(BASE + "/internal/directions"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThat(directions.list()).isEmpty();
    }
}
