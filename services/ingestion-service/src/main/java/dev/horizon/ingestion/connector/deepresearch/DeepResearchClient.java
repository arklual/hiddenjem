package dev.horizon.ingestion.connector.deepresearch;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import dev.horizon.ingestion.connector.support.ConnectorException;

/**
 * Вызов {@code POST /deep-research} сервиса моделей.
 *
 * <p>Интерфейс, а не класс: тест коннектора подставляет записанный ответ, и цикл агента ради
 * проверки разбора запускать незачем. Ответ — тело как есть: коннектор архивирует его целиком,
 * вместе со следом исследования.
 */
@FunctionalInterface
public interface DeepResearchClient {

    /**
     * Тело ответа сервиса моделей. Исключение — отказ источника.
     *
     * @param timeout сколько ждать ответа. Задаётся на каждый запрос, а не при сборке клиента:
     *     бюджет исследования зависит от режима анализа, и ожидание, рассчитанное на быстрый режим,
     *     обрывало бы качественный на седьмой минуте из двадцати
     */
    String research(String requestJson, Duration timeout);

    /**
     * Клиент поверх {@link HttpClient}.
     *
     * <p>Не общий {@code ConnectorHttpClient}: у того таймаут ответа тридцать секунд и повторы, а
     * исследование идёт минуты, и повторять его целиком — значит удвоить время саги. HTTP/1.1
     * явно, по той же причине, что у расширения запроса: на h2c-апгрейде uvicorn теряет тело.
     */
    static DeepResearchClient http(String nlpUrl) {
        return http(nlpUrl, "/deep-research", DeepResearchConnector.SOURCE_ID, "глубокое исследование");
    }

    /** Тот же клиент к другому эндпоинту сервиса моделей с ответом той же формы (веб-корпус). */
    static DeepResearchClient http(String nlpUrl, String path, String sourceId, String what) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        URI uri = URI.create(nlpUrl.replaceAll("/+$", "") + path);
        return (body, timeout) -> {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            try {
                HttpResponse<String> response =
                        http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (response.statusCode() != 200) {
                    throw new ConnectorException.Permanent(
                            sourceId,
                            response.statusCode(),
                            "Сервис моделей ответил " + response.statusCode() + " на " + what);
                }
                return response.body();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ConnectorException.Permanent(sourceId, 0, "Прервано: " + what);
            } catch (java.io.IOException e) {
                throw new ConnectorException.Permanent(sourceId, 0, "Сервис моделей недоступен: " + e);
            }
        };
    }
}
