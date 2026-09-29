package dev.horizon.trends.adapter.analytics;

import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import dev.horizon.trends.application.port.DirectionCrosswalk;

/**
 * Предметные коды направления из движка.
 *
 * <p>Вызов синхронный и стоит в пути приёма запроса, поэтому отказ движка не имеет права стать
 * отказом приёма: без кодов сбор идёт по словам самого запроса — ровно так, как шёл до появления
 * этого шага. Выдача при этом хуже, но запрос принят, и повтор его починит.
 */
@Component
public class HttpDirectionCrosswalk implements DirectionCrosswalk {

    private static final Logger log = LoggerFactory.getLogger(HttpDirectionCrosswalk.class);

    private final RestClient client;
    private final String token;

    public HttpDirectionCrosswalk(
            RestClient analyticsRestClient, @Value("${horizon.analytics.internal-token:}") String token) {
        this.client = analyticsRestClient;
        this.token = token;
    }

    @Override
    public List<String> targetsFor(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        try {
            Response response = client.post()
                    .uri("/internal/directions/resolve")
                    .header("X-Internal-Token", token)
                    .body(Map.of("query", query))
                    .retrieve()
                    .body(Response.class);
            return response == null || response.targets() == null ? List.of() : List.copyOf(response.targets());
        } catch (RestClientException e) {
            // Предупреждение, а не ошибка: запрос будет принят и собран, просто по словам запроса.
            log.warn("Коды направления «{}» недоступны, сбор пойдёт по словам запроса: {}", query, e.toString());
            return List.of();
        }
    }

    @Override
    public Resolution resolve(String query) {
        if (query == null || query.isBlank()) {
            return Resolution.unknown();
        }
        try {
            Response response = client.post()
                    .uri("/internal/directions/resolve")
                    .header("X-Internal-Token", token)
                    .body(Map.of("query", query))
                    .retrieve()
                    .body(Response.class);
            if (response == null) {
                return Resolution.unknown();
            }
            return new Resolution(response.recognized(), response.suggestions());
        } catch (RestClientException e) {
            // Движок недоступен — вердикта нет. Показать «не распознано» было бы враньём, а
            // запретить запуск — превратить подсказку в условие работы.
            log.warn("Проверка направления «{}» недоступна: {}", query, e.toString());
            return Resolution.unknown();
        }
    }

    /** Форма ответа {@code /internal/directions/resolve}. */
    public record Response(List<String> targets, boolean recognized, List<String> suggestions) {}
}
