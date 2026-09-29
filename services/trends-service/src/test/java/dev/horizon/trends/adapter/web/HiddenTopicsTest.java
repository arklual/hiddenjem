package dev.horizon.trends.adapter.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.horizon.trends.domain.feedback.TrendFeedback;
import dev.horizon.trends.domain.report.TrendReportId;
import dev.horizon.trends.support.Fixtures;

/**
 * Что именно скрыто из отчёта пометкой аналитика.
 *
 * <p>Движок убирает помеченные темы до отбора в ТОП-N, поэтому в отчёте их нет вовсе, а число
 * скрытого приходит вместе с покрытием. Числа недостаточно: «скрыто тем: 3» без имён — оговорка,
 * которую нельзя проверить, и отменить решение по ней тоже нельзя. Фильтр, который нельзя осмотреть,
 * честен наполовину.
 */
class HiddenTopicsTest {

    private static TrendFeedback mark(String trendKey, TrendFeedback.Verdict verdict) {
        return TrendFeedback.record(
                Fixtures.USER_ID, new TrendReportId(UUID.randomUUID()), trendKey, verdict, null, Fixtures.NOW);
    }

    @Test
    void onlyNoiseHidesATopic() {
        // «Мы это уже знаем» — тема настоящая, и её исчезновение из отчёта было бы враньём: аналитик
        // сказал, что знает о ней, а не что её не существует.
        var marks = List.of(
                mark("stale document", TrendFeedback.Verdict.NOISE),
                mark("speculative decod", TrendFeedback.Verdict.ALREADY_KNOWN),
                mark("linear-time model", TrendFeedback.Verdict.RELEVANT));

        assertThat(HiddenTopics.keysToHide(marks, Set.of())).containsExactly("stale document");
    }

    @Test
    void aTopicStillInTheReportIsNotCalledHidden() {
        // Его пометка видна на самой карточке. Повторить её сверху — значит сказать, что тема
        // скрыта, когда она на экране.
        var marks = List.of(mark("stale document", TrendFeedback.Verdict.NOISE));

        assertThat(HiddenTopics.keysToHide(marks, Set.of("stale document"))).isEmpty();
    }

    @Test
    void theOrderIsStable() {
        // Порядок из базы не определён, а список, переставляющийся от запроса к запросу, выглядит
        // так, будто скрытое меняется само.
        var marks = List.of(
                mark("zeta", TrendFeedback.Verdict.NOISE),
                mark("alpha", TrendFeedback.Verdict.NOISE),
                mark("mu", TrendFeedback.Verdict.NOISE));

        assertThat(HiddenTopics.keysToHide(marks, Set.of())).containsExactly("alpha", "mu", "zeta");
    }

    @Test
    void theHumanTitleIsShownWhenItIsKnown() {
        // Ключ стеммирован: «retriev passage». Показать аналитику его же решение в таком виде —
        // значит потребовать расшифровки того, что он сам сделал.
        var described =
                HiddenTopics.describe(List.of("retriev passage"), Map.of("retriev passage", "retrieved passages"));

        assertThat(described).singleElement().satisfies(topic -> {
            assertThat(topic.title()).isEqualTo("retrieved passages");
            assertThat(topic.trendKey()).isEqualTo("retriev passage");
        });
    }

    @Test
    void aTopicWithoutAKnownTitleIsStillListed() {
        // Иначе число над списком и сам список разойдутся, и доверия не будет ни тому, ни другому.
        var described = HiddenTopics.describe(List.of("unseen key"), Map.of());

        assertThat(described).singleElement().satisfies(topic -> assertThat(topic.title())
                .isEqualTo("unseen key"));
    }
}
