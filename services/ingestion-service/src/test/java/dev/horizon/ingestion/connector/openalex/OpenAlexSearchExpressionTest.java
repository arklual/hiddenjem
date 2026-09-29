package dev.horizon.ingestion.connector.openalex;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Поисковое выражение OpenAlex.
 *
 * <p>Замер, из которого взялось правило: цели направления «искусственный интеллект», склеенные
 * в мешок слов, находят в OpenAlex 30 работ за 2019–2026, а те же цели фразами через {@code OR} —
 * 5 420 503. OpenAlex соединяет слова запроса через И, и работа обязана была упомянуть разом
 * «cs.AI», «stat.ML» и «computer vision».
 */
class OpenAlexSearchExpressionTest {

    /** Ровно то, что перекрёстный словарь отдаёт для направления «искусственный интеллект». */
    private static final List<String> AI_DIRECTION = List.of(
            "ai",
            "artificial intelligence",
            "computer vision",
            "cs.AI",
            "cs.CL",
            "cs.CV",
            "cs.LG",
            "deep learning",
            "machine learning",
            "natural language processing",
            "nlp",
            "stat.ML");

    @Test
    @DisplayName("Цели направления соединяются через ИЛИ, а не через И")
    void joinsTargetsWithOr() {
        assertThat(OpenAlexConnector.searchExpression(AI_DIRECTION))
                .contains("\"machine learning\" OR \"natural language processing\"");
    }

    @Test
    @DisplayName("Коды классификатора arXiv в поиск OpenAlex не идут: в текстах работ их нет")
    void dropsClassificationCodes() {
        assertThat(OpenAlexConnector.searchExpression(AI_DIRECTION))
                .doesNotContain("cs.AI", "cs.LG", "stat.ML");
    }

    @Test
    @DisplayName("Запрос аналитика на языке источника уходит как есть")
    void keepsAnalystWording() {
        assertThat(OpenAlexConnector.searchExpression(List.of("quantum error correction")))
                .isEqualTo("quantum error correction");
    }

    @Test
    @DisplayName("Кавычки внутри цели не ломают выражение")
    void stripsEmbeddedQuotes() {
        assertThat(OpenAlexConnector.searchExpression(List.of("a \"b\" c", "d e")))
                .isEqualTo("\"a b c\" OR \"d e\"");
    }
}
