package dev.horizon.trends.domain.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * Grouping and order of found topics (BR-A70, BR-A71, BR-A72).
 *
 * <p>База отвечает за отбор и границу доступа, домен — за то, как это читается, и проверяется он
 * здесь без базы. Порядок тут не косметика: он утверждает, что важнее, и молча зависеть от плана
 * запроса не имеет права.
 */
class TopicSearchResultTest {

    private static final Instant MARCH = Instant.parse("2026-03-01T10:00:00Z");
    private static final UUID REPORT_A = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001");
    private static final UUID REPORT_B = UUID.fromString("bbbbbbbb-0000-4000-8000-000000000002");
    private static final UUID REPORT_C = UUID.fromString("cccccccc-0000-4000-8000-000000000003");

    private static TopicOccurrence found(
            String trendKey, String title, int rank, UUID reportId, String direction, Instant when) {
        return new TopicOccurrence(trendKey, title, rank, reportId, 1, direction, direction, when);
    }

    @Test
    void oneTopicSeenInThreeReportsIsOneRowWithThreeOccurrences() {
        // P4: без группировки выдачу займут версии одного направления — три пересчёта дадут три
        // строки об одном и том же, и аналитик решит, что тема встречается трижды.
        var result = TopicSearchResult.of(
                List.of(
                        found("federated-learning", "Federated learning", 3, REPORT_A, "скоринг", MARCH),
                        found(
                                "federated-learning",
                                "Federated learning",
                                5,
                                REPORT_B,
                                "скоринг",
                                MARCH.minusSeconds(60)),
                        found(
                                "federated-learning",
                                "Federated learning",
                                9,
                                REPORT_C,
                                "скоринг",
                                MARCH.minusSeconds(120))),
                25,
                false);

        assertThat(result.topics()).hasSize(1);
        assertThat(result.topics().get(0).occurrences()).hasSize(3);
        assertThat(result.topics().get(0).directions()).isOne();
    }

    @Test
    void aTopicFoundInTwoDirectionsOutranksOneFoundDeeperInASingleDirection() {
        // Смысл всей выдачи: тема, независимо всплывшая в двух несмежных направлениях, весит больше
        // найденной в одном — даже если в одном она стоит выше. Контраст обязателен, иначе тест не
        // отличил бы «сортирует по направлениям» от «сортирует по месту».
        var result = TopicSearchResult.of(
                List.of(
                        found("single", "Одно направление", 1, REPORT_A, "скоринг", MARCH),
                        found("converging", "Два направления", 4, REPORT_B, "скоринг", MARCH),
                        found("converging", "Два направления", 7, REPORT_C, "антифрод", MARCH)),
                25,
                false);

        assertThat(result.topics())
                .extracting(TopicSearchResult.Topic::trendKey)
                .containsExactly("converging", "single");
        assertThat(result.topics().get(0).directions()).isEqualTo(2);
    }

    @Test
    void topicsWithTheSameStrengthKeepAStableOrder() {
        // ADR-0015: два одинаково сильных ключа не должны меняться местами между одинаковыми
        // запросами. Вход подан в обратном алфавитном порядке нарочно — иначе тест подтвердил бы
        // лишь то, что порядок входа сохраняется.
        var result = TopicSearchResult.of(
                List.of(
                        found("омега", "Омега", 2, REPORT_A, "скоринг", MARCH),
                        found("альфа", "Альфа", 2, REPORT_B, "скоринг", MARCH)),
                25,
                false);

        assertThat(result.topics())
                .extracting(TopicSearchResult.Topic::trendKey)
                .containsExactly("альфа", "омега");
    }

    @Test
    void theTitleComesFromTheFreshestOccurrence() {
        // Тему могли переименовать между версиями. Показать старое имя значило бы ответить про
        // прошлое на вопрос о настоящем.
        var result = TopicSearchResult.of(
                List.of(
                        found("key", "Старое имя", 3, REPORT_A, "скоринг", MARCH.minusSeconds(3600)),
                        found("key", "Новое имя", 3, REPORT_B, "скоринг", MARCH)),
                25,
                false);

        assertThat(result.topics().get(0).title()).isEqualTo("Новое имя");
    }

    @Test
    void occurrencesRunFromNewestToOldest() {
        var result = TopicSearchResult.of(
                List.of(
                        found("key", "Тема", 3, REPORT_A, "скоринг", MARCH.minusSeconds(3600)),
                        found("key", "Тема", 4, REPORT_B, "скоринг", MARCH)),
                25,
                false);

        assertThat(result.topics().get(0).occurrences())
                .extracting(TopicOccurrence::reportId)
                .containsExactly(REPORT_B, REPORT_A);
    }

    @Test
    void theBestRankIsTheHighestPlaceTheTopicEverTook() {
        // Лучшее место, а не место последнего вхождения: вопрос аналитика — насколько высоко тема
        // вообще поднималась, а не где она оказалась в последнем пересчёте.
        var result = TopicSearchResult.of(
                List.of(
                        found("key", "Тема", 12, REPORT_A, "скоринг", MARCH),
                        found("key", "Тема", 2, REPORT_B, "скоринг", MARCH.minusSeconds(3600))),
                25,
                false);

        assertThat(result.topics().get(0).bestRank()).isEqualTo(2);
    }

    @Test
    void occurrencesBeyondTheShownFewAreCountedRatherThanDropped() {
        // P7: «показаны все» и «показаны пять из семи» — разные утверждения о том, насколько давно
        // тема в работе. Молча отброшенные два превратили бы второе в первое.
        var occurrences = new java.util.ArrayList<TopicOccurrence>();
        for (int i = 0; i < TopicSearchResult.OCCURRENCES_SHOWN + 2; i++) {
            occurrences.add(found("key", "Тема", 3, UUID.randomUUID(), "скоринг", MARCH.minusSeconds(i * 60L)));
        }

        var topic = TopicSearchResult.of(occurrences, 25, false).topics().get(0);

        assertThat(topic.occurrences()).hasSize(TopicSearchResult.OCCURRENCES_SHOWN);
        assertThat(topic.hiddenOccurrences()).isEqualTo(2);
    }

    @Test
    void aTopicThatFitsEntirelyHidesNothing() {
        var topic = TopicSearchResult.of(List.of(found("key", "Тема", 3, REPORT_A, "скоринг", MARCH)), 25, false)
                .topics()
                .get(0);

        assertThat(topic.hiddenOccurrences()).isZero();
    }

    @Test
    void theLimitCutsTopicsButTheOccurrenceCountStillDescribesTheWholeSearch() {
        // Иначе знаменатель описывал бы показанное, и «3 из 3» стояло бы там, где просмотрено пять.
        var result = TopicSearchResult.of(
                List.of(
                        found("первая", "Первая", 1, REPORT_A, "скоринг", MARCH),
                        found("вторая", "Вторая", 2, REPORT_B, "скоринг", MARCH),
                        found("третья", "Третья", 3, REPORT_C, "скоринг", MARCH)),
                2,
                false);

        assertThat(result.topics()).hasSize(2);
        assertThat(result.totalOccurrences()).isEqualTo(3);
    }

    @Test
    void nothingFoundIsAnAnswerAndNotAFailure() {
        var result = TopicSearchResult.of(List.of(), 25, false);

        assertThat(result.topics()).isEmpty();
        assertThat(result.totalOccurrences()).isZero();
        assertThat(result.truncated()).isFalse();
    }
}
