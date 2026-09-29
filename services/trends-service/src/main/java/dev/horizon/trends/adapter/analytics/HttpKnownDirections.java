package dev.horizon.trends.adapter.analytics;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import dev.horizon.trends.application.port.KnownDirections;

/**
 * Список известных направлений из движка.
 *
 * <p>Отказ движка не превращается в отказ экрана: подсказка — помощь, а не условие работы. Аналитик,
 * у которого её не показали, вводит направление сам, как делал раньше; аналитик, у которого экран не
 * открылся, не делает ничего.
 */
@Component
public class HttpKnownDirections implements KnownDirections {

    private static final Logger log = LoggerFactory.getLogger(HttpKnownDirections.class);

    private final RestClient client;
    private final String token;

    public HttpKnownDirections(
            RestClient analyticsRestClient, @Value("${horizon.analytics.internal-token:}") String token) {
        this.client = analyticsRestClient;
        this.token = token;
    }

    @Override
    public List<String> list() {
        try {
            Response response = client.get()
                    .uri("/internal/directions")
                    .header("X-Internal-Token", token)
                    .retrieve()
                    .body(Response.class);
            return response == null || response.directions() == null ? List.of() : List.copyOf(response.directions());
        } catch (RestClientException e) {
            log.warn("Список известных направлений недоступен: {}", e.toString());
            return List.of();
        }
    }

    /** Форма ответа {@code /internal/directions}. */
    public record Response(List<String> directions) {}
}
