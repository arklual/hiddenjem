package dev.horizon.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

/**
 * Verifies the guarantees the edge makes to every client, with a stubbed upstream.
 *
 * <p>Входа в продукте нет: организация одна, и API открыт так же, как интерфейс. Край по-прежнему
 * обещает единый {@code problem+json}, заголовки безопасности на любом ответе, список разрешённых
 * источников и маршрут до службы для каждого опубликованного пути. Redis здесь не нужен — ограничение
 * частоты выключено и проверено своим модульным тестом, так что падение этого класса всегда
 * означает сломанный контракт края.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayEdgeContractTest {

    private static WireMockServer upstream;

    @Autowired
    private WebTestClient client;

    @BeforeAll
    static void startUpstream() {
        upstream = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        upstream.start();
    }

    /** Журнал запросов заглушки общий на класс — перед каждой проверкой он очищается. */
    @BeforeEach
    void clearRequestJournal() {
        upstream.resetRequests();
    }

    @AfterAll
    static void stopUpstream() {
        if (upstream != null) {
            upstream.stop();
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String base = "http://localhost:" + upstream.port();
        registry.add("HORIZON_UPSTREAM_TRENDS_URL", () -> base);
        registry.add("HORIZON_UPSTREAM_INGESTION_URL", () -> base);
        // Rate limiting needs Redis; it has dedicated unit coverage and is out of scope here.
        registry.add("horizon.gateway.rate-limit-enabled", () -> "false");
        registry.add("spring.data.redis.host", () -> "localhost");
    }

    @Test
    @DisplayName("запрос без какого-либо токена доходит до службы")
    void forwardsWithoutAnyToken() {
        upstream.stubFor(WireMock.get(WireMock.urlPathMatching("/api/v1/reports/.*"))
                .willReturn(WireMock.okJson("{\"id\":\"r\"}")));

        client.get()
                .uri("/api/v1/reports/00000000-0000-7000-8000-000000000000")
                .exchange()
                .expectStatus()
                .isOk();

        upstream.verify(1, WireMock.getRequestedFor(WireMock.urlPathMatching("/api/v1/reports/.*"))
                .withoutHeader("Authorization"));
    }

    @Test
    @DisplayName("очередь словаря направлений уходит в trends-service, а не в удалённую службу входа")
    void routesTheLexiconQueueToTrends() {
        upstream.stubFor(WireMock.get(WireMock.urlPathEqualTo("/api/v1/admin/unrecognized-directions"))
                .willReturn(WireMock.okJson("[]")));

        client.get()
                .uri("/api/v1/admin/unrecognized-directions")
                .exchange()
                .expectStatus()
                .isOk();
    }

    @Test
    @DisplayName("любой ответ несёт заголовки безопасности")
    void appliesSecurityHeadersToEveryResponse() {
        // Статус намеренно не проверяется: утверждение — «любой ответ несёт заголовки», а готовность
        // без Redis отвечает 503, и привязка к 200 проверяла бы не то.
        var headers = client.get()
                .uri("/actuator/health")
                .exchange()
                .returnResult(String.class)
                .getResponseHeaders();

        assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(headers.getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(headers.getFirst("Referrer-Policy")).isEqualTo("strict-origin-when-cross-origin");
    }

    @Test
    @DisplayName("неизвестный маршрут возвращает problem+json, а не HTML-страницу ошибки")
    void unknownRouteReturnsProblemJson() {
        client.get()
                .uri("/api/v1/definitely-not-a-route")
                .exchange()
                .expectStatus()
                .value(status -> assertThat(status).isGreaterThanOrEqualTo(400))
                .expectHeader()
                .contentTypeCompatibleWith("application/problem+json")
                .expectBody()
                .jsonPath("$.type")
                .exists()
                .jsonPath("$.status")
                .exists();
    }

    @Test
    @DisplayName("preflight-запрос разрешён только для источников из allowlist")
    void corsAllowlistIsEnforced() {
        client.options()
                .uri("/api/v1/research-requests")
                .header("Origin", "http://localhost:3000")
                .header("Access-Control-Request-Method", "POST")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .valueEquals("Access-Control-Allow-Origin", "http://localhost:3000");

        client.options()
                .uri("/api/v1/research-requests")
                .header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "POST")
                .exchange()
                .expectStatus()
                .isForbidden();
    }

    /**
     * Ни один опубликованный путь не отвечает 401 или 403: входа нет, и отказ «нужна авторизация»
     * был бы остатком удалённой проверки — интерфейс, в котором нечем войти, упёрся бы в него
     * без выхода.
     */
    @Test
    @DisplayName("ни один опубликованный путь не требует входа")
    void noPublishedPathAsksForALogin() throws Exception {
        var refused = new ArrayList<String>();
        for (var published : publishedPaths()) {
            var status = client.get()
                    .uri(concrete(published))
                    .exchange()
                    .returnResult(String.class)
                    .getStatus();
            if (status == HttpStatus.UNAUTHORIZED || status == HttpStatus.FORBIDDEN) {
                refused.add(published + " → " + status);
            }
        }

        assertThat(refused).as("эти пути всё ещё требуют входа, которого нет").isEmpty();
    }

    /** Путь с подставленными параметрами: шаблон в адресе не отправишь. */
    private static String concrete(String published) {
        return published.replaceAll("\\{[^}]+\\}", "00000000-0000-7000-8000-000000000000");
    }

    @SuppressWarnings("unchecked")
    private static List<String> publishedPaths() throws Exception {
        var root = java.nio.file.Path.of(System.getProperty("user.dir"))
                .getParent()
                .getParent();
        try (var in = java.nio.file.Files.newInputStream(root.resolve("contracts/openapi/horizon-api.yaml"))) {
            var spec = (Map<String, Object>) new org.yaml.snakeyaml.Yaml().load(in);
            return List.copyOf(((Map<String, Object>) spec.get("paths")).keySet());
        }
    }
}
