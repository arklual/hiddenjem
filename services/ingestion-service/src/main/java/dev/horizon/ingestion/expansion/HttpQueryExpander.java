package dev.horizon.ingestion.expansion;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizon.ingestion.config.QueryExpansionProperties;
import dev.horizon.ingestion.domain.port.QueryExpander;

/**
 * Узкие запросы от сервиса моделей ({@code POST /expand-query}).
 *
 * <p>Любой отказ — сеть, таймаут, неразобранный ответ — это пустое расширение и предупреждение в
 * журнале, а не отказ сбора: без узких запросов корпус беднее, но собирается как прежде.
 */
public class HttpQueryExpander implements QueryExpander {

    private static final Logger log = LoggerFactory.getLogger(HttpQueryExpander.class);

    private final QueryExpansionProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient http;

    public HttpQueryExpander(QueryExpansionProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        // HTTP/1.1 явно. По умолчанию клиент предлагает серверу h2c через Upgrade, а uvicorn на таком
        // запросе теряет тело: сервис моделей видел пустой POST и отвечал 422 на каждое расширение.
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .build();
    }

    @Override
    public Expansion expand(String query, List<String> subjectTargets, int limit, List<String> languages) {
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "query", query,
                    "targets", subjectTargets == null ? List.of() : subjectTargets,
                    "limit", limit,
                    "languages", languages == null || languages.isEmpty() ? List.of("en") : languages));
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.nlpUrl().replaceAll("/+$", "") + "/expand-query"))
                    .timeout(properties.timeout())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                log.warn("Расширение запроса «{}» недоступно: сервис моделей ответил {}", query, response.statusCode());
                return Expansion.EMPTY;
            }
            JsonNode root = objectMapper.readTree(response.body());
            List<ExpandedQuery> queries = new ArrayList<>();
            for (JsonNode item : root.path("queries")) {
                String text = item.path("query").asText("").trim();
                if (!text.isEmpty() && text.length() <= 200) {
                    queries.add(new ExpandedQuery(
                            text, item.path("group").asText("core"), item.path("language").asText("en")));
                }
            }
            return new Expansion(queries, root.path("model").asText(null));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Expansion.EMPTY;
        } catch (Exception e) {
            log.warn("Расширение запроса «{}» недоступно: {}", query, e.toString());
            return Expansion.EMPTY;
        }
    }
}
