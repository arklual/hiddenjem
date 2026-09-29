package dev.horizon.trends.adapter.web.mapper;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import dev.horizon.trends.adapter.web.dto.TrendFeedbackView;
import dev.horizon.trends.adapter.web.dto.TrendReportView;
import dev.horizon.trends.config.FeatureFlag;
import dev.horizon.trends.config.FeatureGate;
import dev.horizon.trends.domain.feedback.TrendFeedback;
import dev.horizon.trends.domain.report.Coverage;
import dev.horizon.trends.domain.report.DirectionPortrait;
import dev.horizon.trends.domain.report.EmergenceAssessment;
import dev.horizon.trends.domain.report.Evidence;
import dev.horizon.trends.domain.report.Motivation;
import dev.horizon.trends.domain.report.RankedTrend;
import dev.horizon.trends.domain.report.TrendLocalization;
import dev.horizon.trends.domain.report.TrendReport;
import dev.horizon.trends.domain.report.TrendReportId;

/**
 * Maps {@link TrendReport} onto the published shape.
 *
 * <p>The analyst's own verdicts are merged in here rather than being fetched by the client in a
 * second call: the UI shows the rating inline on every trend card, and a separate round trip per
 * report would be a guaranteed waterfall on the read path that NFR-P1 budgets at 300 ms. Feedback is
 * supplied as a pre-fetched map, so this class stays a pure function.
 */
@Component
public class ReportViewMapper {

    private final FeatureGate features;

    public ReportViewMapper(FeatureGate features) {
        this.features = features;
    }

    public TrendReportView toView(
            TrendReport report,
            Map<String, Mark> feedbackByTrendKey,
            List<TrendReportView.HiddenTopicView> hiddenTopics) {
        return new TrendReportView(
                report.id().value(),
                report.researchRequestId().value(),
                report.version(),
                report.previousVersionId().map(TrendReportId::value).orElse(null),
                report.query().raw(),
                report.query().normalized(),
                report.methodology().version(),
                report.methodology().profileId(),
                report.methodology().aggregator(),
                report.methodology().engine(),
                report.methodology().mode(),
                report.corpusSnapshotId(),
                toView(report.coverage()),
                report.truncated(),
                report.trends().stream()
                        .map(trend -> toView(trend, feedbackByTrendKey.get(trend.trendKey())))
                        .toList(),
                features.isEnabled(FeatureFlag.DIRECTION_PORTRAIT)
                        ? TrendReportView.DirectionPortraitView.from(DirectionPortrait.of(report))
                        : null,
                hiddenTopics,
                report.generatedAt());
    }

    public TrendReportView.CoverageView toView(Coverage coverage) {
        return new TrendReportView.CoverageView(
                coverage.documentsAnalyzed(),
                coverage.candidatesEvaluated(),
                coverage.sourcesUsed(),
                coverage.unavailableSources(),
                coverage.partial(),
                coverage.directionRecognized(),
                coverage.directionSuggestions(),
                coverage.suppressedByAnalyst(),
                coverage.corpusTruncated(),
                coverage.exclusions().stream()
                        .map(exclusion -> new TrendReportView.ExclusionView(
                                exclusion.code(), exclusion.reason(), exclusion.count(), exclusion.examples()))
                        .toList(),
                coverage.windowFrom(),
                coverage.windowTo());
    }

    public TrendReportView.RankedTrendView toView(RankedTrend trend, Mark feedback) {
        return new TrendReportView.RankedTrendView(
                trend.rank(),
                trend.trendKey(),
                trend.title(),
                trend.definition(),
                toView(trend.motivation()),
                trend.caseExampleOptional()
                        .map(example -> new TrendReportView.CaseExampleView(
                                example.organization(),
                                example.organizationType() == null
                                        ? null
                                        : example.organizationType().name(),
                                example.country(),
                                example.summary(),
                                example.evidenceIndex(),
                                example.basis() == null ? null : example.basis().name()))
                        .orElse(null),
                toView(trend.assessment()),
                trend.lifecycleStage().name(),
                trend.firstMentionYear(),
                trend.totalDocuments(),
                trend.burstOptional()
                        .map(burst -> new TrendReportView.BurstView(burst.startPeriod(), burst.weight()))
                        .orElse(null),
                trend.timeline().stream()
                        .map(point -> new TrendReportView.TimelinePointView(
                                point.period(), point.documentCount(), point.dov(), point.dod()))
                        .toList(),
                trend.evidence().stream().map(ReportViewMapper::toView).toList(),
                feedback == null ? null : TrendFeedbackView.from(feedback.feedback(), feedback.carried()),
                trend.directionShare(),
                trend.lowCredibilityOnly(),
                toView(trend.localization()),
                trend.explanation().stream()
                        .map(item -> new TrendReportView.ExplanationView(item.title(), item.text()))
                        .toList());
    }

    /**
     * Русский слой или {@code null}.
     *
     * <p>Пустой слой не передаётся вовсе: объект со всеми полями {@code null} читался бы клиентом
     * как «перевод есть, но пустой», и подпись «машинный перевод» встала бы рядом с отсутствующим
     * текстом.
     */
    private TrendReportView.LocalizationView toView(TrendLocalization localization) {
        if (localization == null || localization.empty()) {
            return null;
        }
        return new TrendReportView.LocalizationView(
                localization.title(),
                localization.titleModel(),
                localization.titleMode(),
                localization.definition(),
                localization.problem(),
                localization.benefit(),
                localization.evidenceTitles(),
                localization.textModel(),
                localization.textMode(),
                localization.statement(),
                localization.statementModel());
    }

    private TrendReportView.MotivationView toView(Motivation motivation) {
        return new TrendReportView.MotivationView(
                motivation.problem(),
                motivation.benefit(),
                motivation.attributions().stream()
                        .map(attribution -> new TrendReportView.AttributionView(
                                attribution.statement(), attribution.evidenceIndex()))
                        .toList());
    }

    private TrendReportView.EmergenceAssessmentView toView(EmergenceAssessment assessment) {
        return new TrendReportView.EmergenceAssessmentView(
                assessment.score(),
                assessment.confidence(),
                assessment.lowEvidence(),
                assessment.indicators().stream()
                        .map(indicator -> new TrendReportView.IndicatorScoreView(
                                indicator.name(),
                                indicator.value(),
                                indicator.weight(),
                                indicator.multiplier(),
                                indicator.shortfallShare(),
                                indicator.explanation(),
                                indicator.diagnostics().isEmpty() ? null : indicator.diagnostics()))
                        .toList(),
                assessment
                        .rankStabilityOptional()
                        .map(stability -> new TrendReportView.RankStabilityView(stability.best(), stability.worst()))
                        .orElse(null));
    }

    private static TrendReportView.EvidenceView toView(Evidence evidence) {
        return new TrendReportView.EvidenceView(
                evidence.sourceId(),
                evidence.sourceClass().name(),
                evidence.externalId(),
                evidence.title(),
                evidence.authors(),
                evidence.organization(),
                evidence.organizationCountry(),
                evidence.publishedOn(),
                evidence.url(),
                evidence.doi(),
                evidence.citationCount(),
                evidence.relevance(),
                evidence.snippet(),
                evidence.language(),
                evidence.credibility().name(),
                evidence.credibilityBasis(),
                evidence.independent());
    }

    /**
     * One verdict per topic, and whether it was given on this report or an earlier version of the
     * same direction.
     *
     * <p>Kept together rather than in two fields: "my verdict" and "my verdict from last quarter"
     * are the same judgement at different ages, and splitting them would make every client join them
     * back.
     */
    public record Mark(TrendFeedback feedback, boolean carried) {}

    public static Map<String, Mark> byTrendKey(List<TrendFeedback> feedback) {
        return feedback.stream()
                .collect(java.util.stream.Collectors.toMap(
                        TrendFeedback::trendKey, entry -> new Mark(entry, false), (first, second) -> second));
    }

    /**
     * Merges what the analyst said here with what they said about the same topics before (P3).
     *
     * <p>The verdict given on this report always wins: they may have changed their mind, and the
     * last word is the one they said here. A carried verdict is never silently promoted to a current
     * one — it stays flagged until the analyst confirms it, because a mark the product invented on
     * their behalf is indistinguishable from one they made, and they would find their own name on a
     * judgement they never gave.
     */
    public static Map<String, Mark> merge(List<TrendFeedback> onThisReport, List<TrendFeedback> carried) {
        var merged = new java.util.HashMap<String, Mark>();
        for (TrendFeedback entry : carried) {
            merged.put(entry.trendKey(), new Mark(entry, true));
        }
        merged.putAll(byTrendKey(onThisReport));
        return Map.copyOf(merged);
    }
}
