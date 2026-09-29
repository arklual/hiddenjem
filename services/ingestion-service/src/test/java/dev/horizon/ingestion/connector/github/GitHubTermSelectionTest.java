package dev.horizon.ingestion.connector.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Отбор целей направления в запрос GitHub.
 *
 * <p>Замер, из которого взялось правило: направление «искусственный интеллект» раскрывается
 * словарём в двенадцать целей, запрос из них несёт одиннадцать операторов ИЛИ, а поиск GitHub
 * отвергает запрос более чем с пятью — {@code 422 Unprocessable Entity}. В отчёте это выглядело как
 * недоступный источник при живом источнике.
 */
class GitHubTermSelectionTest {

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
    @DisplayName("Целей не больше пяти: шестая означала бы пятый оператор и отказ поиска")
    void capsTermCount() {
        assertThat(GitHubConnector.selectTerms(AI_DIRECTION)).hasSizeLessThanOrEqualTo(5);
    }

    @Test
    @DisplayName("Классификационные коды предметных словарей не расходуют бюджет запроса")
    void dropsClassificationCodes() {
        assertThat(GitHubConnector.selectTerms(AI_DIRECTION))
                .doesNotContain("cs.AI", "cs.CL", "cs.CV", "cs.LG", "stat.ML");
    }

    @Test
    @DisplayName("Словосочетания идут раньше одиночных слов: «ai» находит всё, что угодно")
    void prefersPhrasesOverSingleWords() {
        assertThat(GitHubConnector.selectTerms(AI_DIRECTION))
                .containsExactly(
                        "artificial intelligence",
                        "computer vision",
                        "deep learning",
                        "machine learning",
                        "natural language processing");
    }

    @Test
    @DisplayName("Когда словосочетаний мало, одиночные слова добираются — иначе запрос пуст")
    void fallsBackToSingleWords() {
        assertThat(GitHubConnector.selectTerms(List.of("nlp", "ai", "q-bio.GN")))
                .containsExactly("nlp", "ai");
    }

    @Test
    @DisplayName("Пустые и отсутствующие цели не превращаются в пустую фразу в запросе")
    void skipsBlanks() {
        assertThat(GitHubConnector.selectTerms(java.util.Arrays.asList("machine learning", "", null, "  ")))
                .containsExactly("machine learning");
    }
}
