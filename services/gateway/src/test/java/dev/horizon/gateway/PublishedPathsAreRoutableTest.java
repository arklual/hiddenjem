package dev.horizon.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Everything we published a path for must be reachable through the edge.
 *
 * <p>Написан по следу двух дефектов сразу. Эндпоинт `/api/v1/features` был реализован, покрыт
 * тестами сервиса, вызывался клиентом — и не имел маршрута на шлюзе. Клиент ходит только через
 * шлюз, значит реестр фич отвечал 404 всегда; неизвестный флаг клиент считает включённым, поэтому
 * дыра не проявлялась ничем, кроме того, что выключить фичу было невозможно. Тем же способом чуть
 * не уехал `/api/v1/topics/**`.
 *
 * <p>Сверяются пути контракта с предикатами `Path=` из конфигурации шлюза. Это проверка
 * достижимости, а не маршрутизации: что путь ведёт именно в тот сервис, здесь не утверждается — для
 * этого есть {@code GatewayEdgeContractTest}, который поднимает шлюз с заглушкой. Зато отсутствие
 * маршрута ловится в сборке, а не ревью через два инкремента.
 */
class PublishedPathsAreRoutableTest {

    private static final Path ROOT =
            Path.of(System.getProperty("user.dir")).getParent().getParent();

    @Test
    void everyPublishedPathMatchesSomeGatewayRoute() throws Exception {
        var routes = gatewayPathPredicates();
        assertThat(routes).as("шлюз объявляет хотя бы один маршрут").isNotEmpty();

        var unroutable = new ArrayList<String>();
        for (var published : publishedPaths()) {
            if (routes.stream().noneMatch(pattern -> matches(pattern, published))) {
                unroutable.add(published);
            }
        }

        assertThat(unroutable)
                .as("эти пути опубликованы в контракте, но шлюз их никуда не ведёт — клиент получит 404")
                .isEmpty();
    }

    /**
     * Сопоставление ровно в той мере, в какой его используют маршруты.
     *
     * <p>Хвост из двух звёзд покрывает и сам префикс: в PathPattern Spring он совпадает с нулём
     * сегментов, поэтому маршрут /api/v1/reports/** обслуживает и /api/v1/reports. Первая версия
     * этой проверки об этом не знала и объявила пять живых маршрутов недостижимыми. Сторож, который
     * врёт, хуже отсутствующего, поэтому случай разобран явно.
     */
    private static boolean matches(String pattern, String path) {
        var regex = pattern.endsWith("/**")
                ? Pattern.quote(pattern.substring(0, pattern.length() - "/**".length())) + "(/.*)?"
                : pattern.replace("*", "[^/]+");
        return path.matches(regex);
    }

    @SuppressWarnings("unchecked")
    private static List<String> publishedPaths() throws Exception {
        try (InputStream in = Files.newInputStream(ROOT.resolve("contracts/openapi/horizon-api.yaml"))) {
            var spec = (Map<String, Object>) new Yaml().load(in);
            return List.copyOf(((Map<String, Object>) spec.get("paths")).keySet());
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> gatewayPathPredicates() throws Exception {
        try (InputStream in =
                Files.newInputStream(ROOT.resolve("services/gateway/src/main/resources/application.yml"))) {
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
    private static Object nested(Map<String, Object> root, String... keys) {
        Object current = root;
        for (var key : keys) {
            current = ((Map<String, Object>) current).get(key);
            assertThat(current).as("ключ %s есть в конфигурации шлюза", key).isNotNull();
        }
        return current;
    }
}
