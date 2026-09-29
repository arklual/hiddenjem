package dev.horizon.ingestion.connector.lens;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import dev.horizon.ingestion.connector.support.SearchPhrases;
import dev.horizon.ingestion.domain.run.Cursor;

/**
 * Общее у двух поисков Lens — научного и патентного: тело запроса, заголовки и листание.
 *
 * <p><b>Запрос — телом, ключ — заголовком.</b> Lens принимает и GET с ключом в адресе, но адрес
 * запроса пишется в журнал и в происхождение каждого документа; POST с {@code Authorization: Bearer}
 * держит ключ только в заголовке.
 *
 * <p><b>Листание — смещением.</b> Прокрутка ({@code scroll}) живёт минуту и не переживает паузу
 * ограничителя частоты; смещение работает до десяти тысяч записей, а сбор по направлению берёт
 * заметно меньше.
 */
final class LensSearch {

    /** Смещение с размером страницы не должно выходить за десять тысяч записей (ограничение Lens). */
    static final int MAX_WINDOW = 10_000;
    private static final int MIN_PHRASE_LENGTH = 3;

    private LensSearch() {}

    /**
     * Цели направления — фразами в кавычках через {@code OR}. Внутри кавычек синтаксис
     * {@code query_string} опасен только обратной косой чертой; кавычки {@link SearchPhrases} уже убрал.
     */
    static String queryString(List<String> upstreamTerms) {
        List<String> phrases = SearchPhrases.of(upstreamTerms, MIN_PHRASE_LENGTH);
        return String.join(" OR ", phrases.stream()
                .map(phrase -> "\"" + phrase.replace("\\", "\\\\").replace("\"", " ").trim() + "\"")
                .toList());
    }

    /** Поиск по полям в окне дат публикации: сначала совпадение фраз, затем фильтр по дате. */
    static String body(
            ObjectMapper objectMapper,
            String query,
            List<String> fields,
            LocalDate from,
            LocalDate to,
            int offset,
            int size,
            List<String> include) {
        Map<String, Object> range = new LinkedHashMap<>();
        range.put("gte", from.toString());
        range.put("lte", to.toString());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("query", Map.of("bool", Map.of(
                "must", List.of(Map.of("query_string", Map.of(
                        "query", query,
                        "fields", fields,
                        "default_operator", "and"))),
                "filter", List.of(Map.of("range", Map.of("date_published", range))))));
        body.put("from", offset);
        body.put("size", size);
        body.put("include", include);
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Lens request body is not serialisable", e);
        }
    }

    static Map<String, String> headers(String token) {
        return Map.of(
                HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE,
                HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    static int offset(Cursor cursor) {
        if (cursor == null || cursor.value() == null || cursor.value().isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Integer.parseInt(cursor.value().trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Размер страницы, чтобы не выйти за предел смещения. */
    static int size(int offset, int pageSize) {
        return Math.max(0, Math.min(pageSize, MAX_WINDOW - offset));
    }

    /** Следующая страница — или {@code null}, если эта последняя. */
    static String next(int offset, int size, int received, Integer total) {
        int nextOffset = offset + received;
        boolean last = received == 0
                || received < size
                || nextOffset >= MAX_WINDOW
                || (total != null && nextOffset >= total);
        return last ? null : Integer.toString(nextOffset);
    }
}
