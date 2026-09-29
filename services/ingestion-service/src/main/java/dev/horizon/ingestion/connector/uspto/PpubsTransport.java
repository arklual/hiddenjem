package dev.horizon.ingestion.connector.uspto;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

import dev.horizon.ingestion.connector.support.ConnectorException;

/**
 * Один HTTP-обмен с Patent Public Search.
 *
 * <p><b>Почему не общий {@code ConnectorHttpClient}.</b> Тот умеет только {@code GET} и не отдаёт
 * заголовки ответа, а поиску нужно ровно обратное: сессия открывается {@code POST} с телом
 * {@code -1}, её ключ приходит заголовком {@code x-access-token}, и сам поиск — тоже {@code POST}
 * с JSON. Вежливость, повторы и архив ответа, которые в общем клиенте даёт обвязка, здесь делает
 * {@link UsptoConnector}: темп в шесть запросов в минуту, повтор при {@code 429} с нарастающей
 * паузой и одна замена сессии при {@code 401}.
 *
 * <p>Интерфейс — чтобы тесты подставляли записанные ответы площадки, не ходя в сеть. Статус любой
 * ответ возвращает как есть: что считать отказом, решает коннектор. Исключение — только сбой
 * соединения ({@link ConnectorException.Retryable} со статусом {@code 0}).
 */
@FunctionalInterface
public interface PpubsTransport {

    Reply exchange(String method, URI uri, Map<String, String> headers, String body);

    /** Ответ: статус, тело и заголовки (имена — в нижнем регистре). */
    record Reply(int status, String body, Map<String, String> headers) {

        public Reply {
            body = body == null ? "" : body;
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }

        public String header(String name) {
            return headers.get(name.toLowerCase(Locale.ROOT));
        }
    }

    /** Транспорт поверх {@link HttpClient}: HTTP/1.1, таймауты из настроек коннекторов. */
    static PpubsTransport http(String sourceId, Duration connectTimeout, Duration responseTimeout) {
        HttpClient http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        return (method, uri, headers, body) -> {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(responseTimeout);
            headers.forEach(request::header);
            request.method(
                    method,
                    body == null
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            try {
                HttpResponse<String> response =
                        http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                Map<String, String> received = new java.util.HashMap<>();
                response.headers().map().forEach((name, values) -> {
                    if (!values.isEmpty()) {
                        received.put(name.toLowerCase(Locale.ROOT), values.get(0));
                    }
                });
                return new Reply(response.statusCode(), response.body(), received);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ConnectorException.Permanent(sourceId, 0, "Запрос к USPTO прерван", e);
            } catch (IOException e) {
                throw new ConnectorException.Retryable(sourceId, 0, "USPTO недоступен: " + e, e);
            }
        };
    }
}
