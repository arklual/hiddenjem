package dev.horizon.ingestion.connector.alphaxiv;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import dev.horizon.ingestion.connector.support.ConnectorException;

/** A small Streamable HTTP MCP client for alphaXiv's stateless tools/call endpoint. */
@FunctionalInterface
public interface AlphaXivClient {

    /** Returns the unmodified JSON-RPC response (JSON or SSE) for archival and parsing. */
    String call(String tool, String argumentsJson);

    static AlphaXivClient http(String endpoint, String apiKey, String userAgent) {
        HttpClient http =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        URI uri = URI.create(endpoint);
        return (tool, argumentsJson) -> {
            String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"" + tool
                    + "\",\"arguments\":" + argumentsJson + "}}";
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .header("MCP-Protocol-Version", "2025-06-18")
                    .header("User-Agent", userAgent)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            try {
                HttpResponse<String> response =
                        http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = response.statusCode();
                if (status == 429 || status >= 500) {
                    throw new ConnectorException.Retryable("alphaxiv", status, "alphaXiv MCP ответил " + status);
                }
                if (status != 200) {
                    throw new ConnectorException.Permanent("alphaxiv", status, "alphaXiv MCP ответил " + status);
                }
                if (response.body().length() > 4_000_000) {
                    throw new ConnectorException.Permanent("alphaxiv", 200, "Слишком большой ответ alphaXiv MCP");
                }
                return response.body();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ConnectorException.Permanent("alphaxiv", 0, "Вызов alphaXiv MCP прерван", e);
            } catch (IOException e) {
                throw new ConnectorException.Retryable("alphaxiv", 0, "alphaXiv MCP недоступен", e);
            }
        };
    }
}
