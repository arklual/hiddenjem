package dev.horizon.trends.domain.report;

import java.util.List;
import java.util.Optional;

import dev.horizon.platform.common.util.Guards;

/**
 * One emerging trend inside a report (entity with local identity: {@code rank} within its report).
 *
 * <p>Not an aggregate of its own — it has no life outside the report and its consistency with the
 * report's ranking must be transactional (domain-model doc §8).
 */
public record RankedTrend(
        int rank,
        String trendKey,
        String title,
        String definition,
        Motivation motivation,
        CaseExample caseExample,
        EmergenceAssessment assessment,
        LifecycleStage lifecycleStage,
        int firstMentionYear,
        int totalDocuments,
        Burst burst,
        List<TimelinePoint> timeline,
        List<Evidence> evidence,
        Double directionShare,
        /**
         * Все источники темы низкой доверенности.
         *
         * <p>ТЗ: сведения из соцсетей, блогов, агрегаторов и пресс-релизов «не должны быть
         * единственным основанием для включения технологии в итоговую выдачу… либо сопровождаться
         * отметкой о пониженной доверенности». Первую половину закрывает правило движка, которое
         * отсекает тему без научно-технической основы вовсе; вторую — эта отметка.
         */
        boolean lowCredibilityOnly,
        /** Русский слой карточки; {@link TrendLocalization#NONE}, если моделей не было. */
        TrendLocalization localization,
        /** Почему тема — слабый сигнал и почему такая уверенность; пусто у отчётов до 29.09. */
        List<ExplanationItem> explanation) {

    /** Тема без объяснения — отчёты, выпущенные до его появления. */
    public RankedTrend(
            int rank,
            String trendKey,
            String title,
            String definition,
            Motivation motivation,
            CaseExample caseExample,
            EmergenceAssessment assessment,
            LifecycleStage lifecycleStage,
            int firstMentionYear,
            int totalDocuments,
            Burst burst,
            List<TimelinePoint> timeline,
            List<Evidence> evidence,
            Double directionShare,
            boolean lowCredibilityOnly,
            TrendLocalization localization) {
        this(
                rank,
                trendKey,
                title,
                definition,
                motivation,
                caseExample,
                assessment,
                lifecycleStage,
                firstMentionYear,
                totalDocuments,
                burst,
                timeline,
                evidence,
                directionShare,
                lowCredibilityOnly,
                localization,
                List.of());
    }

    /**
     * Тема без измеренной доли направления.
     *
     * <p>Отчёты, выпущенные до появления величины, её не несут, и отсутствие обязано читаться как
     * «не измеряли», а не как «доля нулевая»: ноль означал бы, что к направлению не отнесён ни один
     * документ темы, — утверждение, которого никто не проверял.
     */
    public RankedTrend(
            int rank,
            String trendKey,
            String title,
            String definition,
            Motivation motivation,
            CaseExample caseExample,
            EmergenceAssessment assessment,
            LifecycleStage lifecycleStage,
            int firstMentionYear,
            int totalDocuments,
            Burst burst,
            List<TimelinePoint> timeline,
            List<Evidence> evidence,
            Double directionShare) {
        this(
                rank,
                trendKey,
                title,
                definition,
                motivation,
                caseExample,
                assessment,
                lifecycleStage,
                firstMentionYear,
                totalDocuments,
                burst,
                timeline,
                evidence,
                directionShare,
                false,
                TrendLocalization.NONE);
    }

    public RankedTrend(
            int rank,
            String trendKey,
            String title,
            String definition,
            Motivation motivation,
            CaseExample caseExample,
            EmergenceAssessment assessment,
            LifecycleStage lifecycleStage,
            int firstMentionYear,
            int totalDocuments,
            Burst burst,
            List<TimelinePoint> timeline,
            List<Evidence> evidence) {
        this(
                rank,
                trendKey,
                title,
                definition,
                motivation,
                caseExample,
                assessment,
                lifecycleStage,
                firstMentionYear,
                totalDocuments,
                burst,
                timeline,
                evidence,
                null);
    }

    public RankedTrend {
        localization = localization == null ? TrendLocalization.NONE : localization;
        explanation = explanation == null ? List.of() : List.copyOf(explanation);
        Guards.requireArgument(rank >= 1, "trend.rank must be positive");
        Guards.requireLength(trendKey, "trend.trendKey", 1, 160);
        Guards.requireLength(title, "trend.title", 1, 200);
        Guards.requireText(definition, "trend.definition");
        Guards.requireNonNull(motivation, "trend.motivation");
        Guards.requireNonNull(assessment, "trend.assessment");
        Guards.requireNonNull(lifecycleStage, "trend.lifecycleStage");
        Guards.requireRange(firstMentionYear, "trend.firstMentionYear", 1900, 2100);
        Guards.requireArgument(totalDocuments >= 1, "trend.totalDocuments must be at least 1");
        Guards.requireNotEmpty(timeline, "trend.timeline");
        // J2: a trend without evidence is not publishable (BR-A6).
        Guards.requireNotEmpty(evidence, "trend.evidence");
        timeline = List.copyOf(timeline);
        evidence = List.copyOf(evidence);
        if (caseExample != null && caseExample.evidenceIndex() >= evidence.size()) {
            throw new IllegalArgumentException(
                    "caseExample ссылается на несуществующий источник: индекс %d при %d источниках"
                            .formatted(caseExample.evidenceIndex(), evidence.size()));
        }
        for (var attribution : motivation.attributions()) {
            if (attribution.evidenceIndex() >= evidence.size()) {
                throw new IllegalArgumentException(
                        "motivation ссылается на несуществующий источник: индекс %d при %d источниках"
                                .formatted(attribution.evidenceIndex(), evidence.size()));
            }
        }
    }

    /**
     * Доля документов темы, отнесённых источниками к направлению отчёта.
     *
     * <p>Величина, на которой держится сам отбор темы в отчёт. У настоящих тем направления она
     * лежит в районе двух третей; тема, набравшая седьмую часть, попала в отчёт потому, что для
     * узкого направления планка опускается почти до нуля (`docs/01-analysis/32-foreign-topic-findings.md`).
     * Статистика их не разделяет, а человек — с одного взгляда, если ему эту величину показать.
     */
    public Optional<Double> directionShareOptional() {
        return Optional.ofNullable(directionShare);
    }

    public Optional<CaseExample> caseExampleOptional() {
        return Optional.ofNullable(caseExample);
    }

    public Optional<Burst> burstOptional() {
        return Optional.ofNullable(burst);
    }
}
