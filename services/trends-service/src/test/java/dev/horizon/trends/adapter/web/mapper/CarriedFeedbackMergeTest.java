package dev.horizon.trends.adapter.web.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.horizon.trends.domain.feedback.TrendFeedback;
import dev.horizon.trends.domain.report.TrendReportId;
import dev.horizon.trends.support.Fixtures;

/**
 * Merging the analyst's marks on this report with the ones they made on earlier versions (BR-A32).
 *
 * <p>Feedback is stored per report, and a recomputation makes a new one — so without carrying, the
 * twenty minutes of triage spent on a quarterly direction is thrown away every quarter, and by the
 * third the analyst stops making it. The rule that matters most here is the one that keeps the
 * feature honest: a carried mark stays labelled as carried until they confirm it, because a
 * judgement the product places on their behalf is indistinguishable from one they made.
 */
class CarriedFeedbackMergeTest {

    private static final TrendReportId THIS_REPORT = new TrendReportId(UUID.randomUUID());
    private static final TrendReportId LAST_QUARTER = new TrendReportId(UUID.randomUUID());

    private static TrendFeedback mark(TrendReportId report, String trendKey, TrendFeedback.Verdict verdict) {
        return TrendFeedback.record(Fixtures.USER_ID, report, trendKey, verdict, null, Fixtures.NOW);
    }

    @Test
    void aMarkFromAnEarlierVersionIsShownAndLabelled() {
        var merged = ReportViewMapper.merge(
                List.of(), List.of(mark(LAST_QUARTER, "федеративное обучение", TrendFeedback.Verdict.NOISE)));

        var carried = merged.get("федеративное обучение");
        assertThat(carried).isNotNull();
        assertThat(carried.feedback().verdict()).isEqualTo(TrendFeedback.Verdict.NOISE);
        assertThat(carried.carried())
                .as("перенесённая оценка не выдаётся за здешнюю")
                .isTrue();
    }

    @Test
    void aMarkMadeOnThisReportIsNotLabelledAsCarried() {
        var merged =
                ReportViewMapper.merge(List.of(mark(THIS_REPORT, "тема", TrendFeedback.Verdict.RELEVANT)), List.of());

        assertThat(merged.get("тема").carried()).isFalse();
    }

    @Test
    void whatTheAnalystSaidHereWins() {
        // P3. They may have changed their mind, and the last word is the one they said on this
        // version — a carried verdict must never override it.
        var merged = ReportViewMapper.merge(
                List.of(mark(THIS_REPORT, "тема", TrendFeedback.Verdict.RELEVANT)),
                List.of(mark(LAST_QUARTER, "тема", TrendFeedback.Verdict.NOISE)));

        var mark = merged.get("тема");
        assertThat(mark.feedback().verdict()).isEqualTo(TrendFeedback.Verdict.RELEVANT);
        assertThat(mark.carried())
                .as("подтверждённая здесь оценка перестаёт быть перенесённой")
                .isFalse();
    }

    @Test
    void topicsMarkedOnlyHereAndOnlyBeforeBothSurvive() {
        var merged = ReportViewMapper.merge(
                List.of(mark(THIS_REPORT, "здешняя", TrendFeedback.Verdict.RELEVANT)),
                List.of(mark(LAST_QUARTER, "прошлая", TrendFeedback.Verdict.ALREADY_KNOWN)));

        assertThat(merged).containsOnlyKeys("здешняя", "прошлая");
        assertThat(merged.get("здешняя").carried()).isFalse();
        assertThat(merged.get("прошлая").carried()).isTrue();
    }

    @Test
    void anUnmarkedTopicStaysUnmarked() {
        // BR-A36: no verdict means no verdict. Defaulting to anything would let the product answer a
        // question the analyst never answered.
        var merged = ReportViewMapper.merge(List.of(), List.of());

        assertThat(merged.get("никем не оценённая")).isNull();
    }

    @Test
    void theCommentTravelsWithTheVerdict() {
        // The comment is the part that carries the reasoning; a verdict without it makes the analyst
        // re-derive why they dismissed the topic, which is most of the work being saved.
        var withComment = TrendFeedback.record(
                Fixtures.USER_ID,
                LAST_QUARTER,
                "тема",
                TrendFeedback.Verdict.NOISE,
                "дубль нашего внутреннего проекта",
                Instant.parse("2025-12-01T10:00:00Z"));

        var merged = ReportViewMapper.merge(List.of(), List.of(withComment));

        assertThat(merged.get("тема").feedback().comment()).isEqualTo("дубль нашего внутреннего проекта");
        assertThat(merged.get("тема").feedback().createdAt()).isEqualTo(Instant.parse("2025-12-01T10:00:00Z"));
    }

    @Test
    void theResultIsImmutableToItsCaller() {
        var merged = ReportViewMapper.merge(List.of(mark(THIS_REPORT, "тема", TrendFeedback.Verdict.NOISE)), List.of());

        assertThat(merged).isUnmodifiable();
    }
}
