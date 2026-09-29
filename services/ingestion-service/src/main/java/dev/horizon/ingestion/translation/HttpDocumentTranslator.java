package dev.horizon.ingestion.translation;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizon.ingestion.domain.port.DocumentTranslations;
import dev.horizon.ingestion.domain.port.DocumentTranslator;
import dev.horizon.ingestion.config.TranslationProperties;

/** Перевод через сервис моделей ({@code POST /translate/documents}). */
public class HttpDocumentTranslator implements DocumentTranslator {

    private final TranslationProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient http;

    public HttpDocumentTranslator(TranslationProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        // HTTP/1.1 явно: предложение h2c через Upgrade uvicorn принимает, теряя тело запроса.
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    @Override
    public Result translate(List<DocumentTranslations.Pending> batch) {
        try {
            List<Map<String, String>> items = new ArrayList<>(batch.size());
            for (DocumentTranslations.Pending document : batch) {
                items.add(Map.of(
                        "id", document.id().toString(),
                        "title", clip(document.title(), 2000),
                        "abstract", clip(document.abstractText(), 20000)));
            }
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(properties.nlpUrl().replaceAll("/+$", "") + "/translate/documents"))
                    .timeout(properties.timeout())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(Map.of("items", items)), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) {
                throw new IllegalStateException("сервис моделей ответил " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body());
            List<DocumentTranslations.Translated> documents = new ArrayList<>();
            for (JsonNode item : root.path("items")) {
                String title = item.path("title").asText("").trim();
                if (title.isEmpty()) {
                    continue;
                }
                String abstractText = item.path("abstract").asText("").trim();
                documents.add(new DocumentTranslations.Translated(
                        UUID.fromString(item.path("id").asText()), title, abstractText.isEmpty() ? null : abstractText));
            }
            return new Result(documents, root.path("model").asText(null));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("перевод прерван", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e.toString(), e);
        }
    }

    private static String clip(String text, int limit) {
        if (text == null) {
            return "";
        }
        return text.length() <= limit ? text : text.substring(0, limit);
    }
}
