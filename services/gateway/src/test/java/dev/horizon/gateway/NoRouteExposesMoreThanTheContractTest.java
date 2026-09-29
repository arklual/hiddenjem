package dev.horizon.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Обратная сторона достижимости: шлюз не выводит наружу ничего сверх контракта.
 *
 * <p>{@link PublishedPathsAreRoutableTest} проверяет одно направление — у каждого пути контракта
 * есть маршрут. Направления «ни один маршрут не выводит пути вне контракта» не проверял никто, а
 * цена ошибки здесь несимметрична: забытый маршрут стоит недоступной возможности и находится
 * первым же обращением, лишний — открывает служебную поверхность и не находится ничем.
 *
 * <p>Поверхность, которую это защищает, существует. `InternalCorpusController` службы сбора живёт на
 * `/internal/v1/**` и отдаёт документы снапшота целиком; маршрута к нему нет, и держится это сейчас
 * только тем, что никто его не добавил. Одна строка в конфигурации — и выгрузка корпуса доступна
 * любому, кто просто вошёл в систему: базовое правило шлюза требует аутентификации, но не роли.
 *
 * <p>Тот же класс ошибки уже описан в самой конфигурации — про actuator-эндпоинт `gateway`, который
 * умеет создавать маршруты на ходу. Там он предотвращён комментарием, и комментарий не краснеет.
 */
class NoRouteExposesMoreThanTheContractTest {

    private static final Path ROOT =
            Path.of(System.getProperty("user.dir")).getParent().getParent();

    private static final Path GATEWAY_CONFIG = Path.of("services/gateway/src/main/resources/application.yml");

    /**
     * Префиксы, которые шлюзу позволено выводить наружу.
     *
     * <p>`/api/v1` — опубликованный контракт, и больше ничего: `/internal`, `/actuator`, `/debug`
     * и всё, что появится с такими же намерениями, наружу не выводится.
     */
    private static final List<String> ALLOWED_PREFIXES = List.of("/api/v1/");

    @Test
    @DisplayName("ни один маршрут не выводит наружу путь вне опубликованного контракта")
    void noRouteReachesOutsideThePublishedContract() throws Exception {
        var predicates = gatewayPathPredicates();

        assertThat(predicates)
                .as("шлюз объявляет хотя бы один маршрут — иначе проверка ниже вечнозелёная")
                .isNotEmpty();

        var outside = predicates.stream()
                .filter(pattern -> ALLOWED_PREFIXES.stream().noneMatch(pattern::startsWith))
                .toList();

        assertThat(outside)
                .as("маршруты за пределами контракта: служебная поверхность наружу не выводится")
                .isEmpty();
    }

    @Test
    @DisplayName("actuator-эндпоинт управления маршрутами не публикуется")
    void theRouteManagementEndpointIsNotExposed() throws Exception {
        // Эндпоинт `gateway` умеет создавать, заменять и удалять маршруты на ходу. Доступный
        // изнутри, он превращает учётную запись аналитика в кражу учётных данных всей платформы:
        // маршрут на свой хост — и каждый заголовок Authorization уходит туда. Конфигурация уже
        // объясняет это словами; здесь то же самое становится исполняемым.
        var exposed = exposedActuatorEndpoints();

        assertThat(exposed)
                .as("список публикуемых actuator-эндпоинтов прочитан")
                .isNotEmpty();
        assertThat(exposed).doesNotContain("gateway").doesNotContain("*");
    }

    @Test
    @DisplayName("служебный путь службы сбора остаётся без маршрута")
    void theIngestionInternalApiHasNoRoute() throws Exception {
        // Именной случай рядом с общим правилом: он называет то, что защищается, и остаётся
        // читаемым, когда общее правило переживёт десяток правок.
        assertThat(gatewayPathPredicates())
                .as("`/internal/v1/**` отдаёт документы снапшота и наружу не выводится")
                .noneMatch(pattern -> pattern.startsWith("/internal"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> gatewayPathPredicates() throws Exception {
        try (InputStream in = Files.newInputStream(ROOT.resolve(GATEWAY_CONFIG))) {
            var config = (Map<String, Object>) new Yaml().load(in);
            var routes = (List<Map<String, Object>>) nested(config, "spring", "cloud", "gateway", "routes");
            var patterns = new ArrayList<String>();
            for (var route : routes) {
                for (var predicate : (List<String>) route.getOrDefault("predicates", List.of())) {
                    if (predicate.startsWith("Path=")) {
                        patterns.add(predicate.substring("Path=".length()));
                    }
                }
            }
            return patterns;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> exposedActuatorEndpoints() throws Exception {
        try (InputStream in = Files.newInputStream(ROOT.resolve(GATEWAY_CONFIG))) {
            var config = (Map<String, Object>) new Yaml().load(in);
            var include = nested(config, "management", "endpoints", "web", "exposure", "include");
            return List.of(String.valueOf(include).split("\\s*,\\s*"));
        }
    }

    @SuppressWarnings("unchecked")
    private static Object nested(Map<String, Object> root, String... keys) {
        Object current = root;
        for (var key : keys) {
            current = ((Map<String, Object>) current).get(key);
            assertThat(current).as("ключ %s есть в конфигурации шлюза", key).isNotNull();
        }
        return current;
    }
}
