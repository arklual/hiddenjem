package dev.horizon.trends.adapter.web.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import dev.horizon.trends.domain.report.DirectionPortrait;

/**
 * OpenAPI {@code TrendReport} and its nested shapes.
 *
 * <p>Grouped in one file because they are one contract: a reader checking the response against the
 * specification wants the whole document in front of them, not twelve files. They are also the only
 * place in the service where the published wire names live, which makes a contract change a
 * single-file change.
 *
 * <p>The methodology is flattened here ({@code methodologyVersion}, {@code methodologyProfileId},
 * {@code scoreAggregator}) whereas the domain keeps a {@code MethodologyRef} value object — the wire
 * format is optimised for consumers, the model for cohesion, and the mapper is what reconciles them.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TrendReportView(
        UUID id,
        UUID researchRequestId,
        int version,
        UUID previousVersionId,
        String query,
        String normalizedQuery,
        String methodologyVersion,
        UUID methodologyProfileId,
        String scoreAggregator,
        /**
         * Чем посчитан отчёт: {@code methodology} | {@code signals}.
         *
         * <p>Рядом с версией методологии по той же причине, по которой она здесь: отчёт, о котором
         * нельзя сказать, чем он получен, — число без происхождения. Новые отчёты считает один
         * движок, {@code signals}; {@code methodology} остаётся подписью отчётов, выпущенных до
         * вывода движка методологии из продукта.
         */
        String engine,
        /** В каком режиме считан отчёт: {@code fast} | {@code quality}. */
        String mode,
        UUID corpusSnapshotId,
        CoverageView coverage,
        boolean truncated,
        List<RankedTrendView> trends,
        /**
         * The direction in the few numbers an analyst has to answer for (BR-A16).
         *
         * <p>Travels with the report rather than behind its own endpoint: it is derived from the
         * very payload being sent, so a second round trip would fetch data the client already holds.
         * That is the opposite of the delta, which needs a second report the client does not have.
         */
        DirectionPortraitView portrait,
        /**
         * Темы, скрытые из этого отчёта по пометке смотрящего.
         *
         * <p>Здесь, а не в покрытии: покрытие описывает отчёт, а этот список — смотрящего. Отчёт
         * неизменяем и общий, у коллеги с другими пометками он покажет другое скрытое.
         *
         * <p>Число скрытого лежит в покрытии и приходит от движка, а имена — отсюда. Расхождение
         * между ними означало бы, что пометки применились не те, и клиент вправе его показать.
         */
        List<HiddenTopicView> hiddenTopics,
        Instant generatedAt) {

    /**
     * Counts only — see {@code DirectionPortrait} for why there is no generated prose here.
     *
     * <p>Deliberately without {@code @JsonInclude(NON_NULL)}, unlike its neighbours in this file:
     * {@code medianFirstMentionYear} must arrive as an explicit {@code null} for an empty report.
     * Dropping the field would let a client read "not sent" as "not applicable", and those are the
     * kind of two readings this whole summary exists to keep apart.
     */
    public record DirectionPortraitView(
            int trendsInReport,
            int candidatesEvaluated,
            int lowEvidenceCount,
            Integer medianFirstMentionYear,
            List<StageCountView> byLifecycleStage,
            LocalDate windowFrom,
            LocalDate windowTo,
            List<String> sourcesUsed,
            List<String> unavailableSources,
            boolean partial,
            boolean directionRecognized,
            List<String> directionSuggestions) {

        public static DirectionPortraitView from(DirectionPortrait portrait) {
            return new DirectionPortraitView(
                    portrait.trendsInReport(),
                    portrait.candidatesEvaluated(),
                    portrait.lowEvidenceCount(),
                    portrait.medianFirstMentionYear(),
                    portrait.byLifecycleStage().stream()
                            .map(stage -> new StageCountView(stage.stage(), stage.count()))
                            .toList(),
                    portrait.windowFrom(),
                    portrait.windowTo(),
                    portrait.sourcesUsed(),
                    portrait.unavailableSources(),
                    portrait.partial(),
                    portrait.directionRecognized(),
                    portrait.directionSuggestions());
        }
    }

    public record StageCountView(String stage, int count) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CoverageView(
            int documentsAnalyzed,
            int candidatesEvaluated,
            List<String> sourcesUsed,
            List<String> unavailableSources,
            boolean partial,
            boolean directionRecognized,
            List<String> directionSuggestions,
            int suppressedByAnalyst,
            /**
             * Корпус обрезан пределом профиля до анализа: часть литературы не рассматривалась.
             *
             * <p>Оговорка доезжала до записки и CSV и не доезжала до экрана — то есть аналитик
             * узнавал о неполноте, только если выгружал файл. Место у неё там, где отчёт читают.
             */
            boolean corpusTruncated,
            /**
             * Почему кандидаты не попали в отчёт: причина словами, число и несколько имён.
             *
             * <p>ТЗ требует показывать причины исключения зрелых технологий и нерелевантных
             * кандидатов, и отдельно — демонстрировать логику исключения хайпа и шума. Пустой
             * список означает «движок их не присылал», и интерфейс тогда блока не показывает: это
             * честнее блока «отсеяно ноль».
             */
            List<ExclusionView> exclusions,
            LocalDate windowFrom,
            LocalDate windowTo) {}

    /** Одна причина исключения: код, формулировка, число и до пяти имён. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ExclusionView(String code, String reason, int count, List<String> examples) {}

    /**
     * Тема, скрытая из отчёта по пометке смотрящего.
     *
     * <p>Ключ и название: ключ стеммирован («retriev passage»), и показывать аналитику его же
     * решение в таком виде — значит требовать расшифровки. Ключ всё же передаётся: по нему клиент
     * отличит строку и сможет отменить пометку.
     */
    public record HiddenTopicView(String trendKey, String title) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RankedTrendView(
            int rank,
            String trendKey,
            String title,
            String definition,
            MotivationView motivation,
            CaseExampleView caseExample,
            EmergenceAssessmentView assessment,
            String lifecycleStage,
            int firstMentionYear,
            int totalDocuments,
            BurstView burst,
            List<TimelinePointView> timeline,
            List<EvidenceView> evidence,
            TrendFeedbackView feedback,
            /**
             * Доля документов темы, отнесённых к направлению отчёта; {@code null} — не измерялась.
             *
             * <p>Величина, на которой держится отбор темы в отчёт. У настоящих тем направления она
             * около двух третей, у чужой — седьмая часть, и статистика их не разделяет. Человек
             * разделяет с одного взгляда, если ему её показать.
             */
            Double directionShare,
            /**
             * Все источники темы низкой доверенности.
             *
             * <p>Отметка, которую ТЗ требует вместо удаления темы: сведения из медиа и
             * пресс-релизов «не должны быть единственным основанием для включения… либо
             * сопровождаться отметкой о пониженной доверенности».
             */
            boolean lowCredibilityOnly,
            /** Русский слой карточки; {@code null} — сервис моделей был выключен. */
            LocalizationView localization,
            /** Почему тема — слабый сигнал и почему такая уверенность; пусто у старых отчётов. */
            List<ExplanationView> explanation) {}

    /** Строка объяснения темы: заголовок и пояснение. */
    public record ExplanationView(String title, String text) {}

    /**
     * Русский слой карточки: перевод рядом с оригиналом (ADR-0017).
     *
     * <p>Имя модели и вид обработки — требование ТЗ, а не подпись для красоты: «при использовании
     * автоматического перевода или генеративного резюме это должно быть отмечено возле
     * источника». Перевод названия и пересказ абстракта имеют разную надёжность.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LocalizationView(
            String title,
            String titleModel,
            String titleMode,
            String definition,
            String problem,
            String benefit,
            List<String> evidenceTitles,
            String textModel,
            String textMode,
            /** Тема, сформулированная как тренд, — одно русское предложение о том, что меняется. */
            String statement,
            String statementModel) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MotivationView(String problem, String benefit, List<AttributionView> attributions) {}

    public record AttributionView(String statement, int evidenceIndex) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CaseExampleView(
            String organization,
            String organizationType,
            String country,
            String summary,
            int evidenceIndex,
            String basis) {}

    // NON_NULL: у отчётов без проверки устойчивости диапазона нет, и поле должно отсутствовать,
    // а не приходить `null` — контракт объявляет его необязательным объектом, не nullable.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EmergenceAssessmentView(
            double score,
            double confidence,
            boolean lowEvidence,
            List<IndicatorScoreView> indicators,
            RankStabilityView rankStability) {}

    /**
     * Лучшее и худшее место темы при разумных изменениях весов индикаторов (методология §16).
     *
     * <p>{@code null}, если устойчивость не измерялась. Отсутствие означает «не проверяли», а не
     * «неустойчиво»: ложная оговорка о шаткости обесценивает настоящие.
     */
    public record RankStabilityView(int best, int worst) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IndicatorScoreView(
            String name,
            double value,
            double weight,
            double multiplier,
            double shortfallShare,
            String explanation,
            Map<String, Object> diagnostics) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record BurstView(String startPeriod, Double weight) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TimelinePointView(String period, int documentCount, Double dov, Double dod) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record EvidenceView(
            String sourceId,
            String sourceClass,
            String externalId,
            String title,
            String authors,
            String organization,
            String organizationCountry,
            LocalDate publishedOn,
            String url,
            String doi,
            Integer citationCount,
            double relevance,
            String snippet,
            /** Язык оригинала, ISO 639-1; {@code null} — источник его не сообщил. */
            String language,
            /** Уровень доверенности: HIGH, MEDIUM или LOW. */
            String credibility,
            /** Правило, присвоившее уровень, словами: ТЗ допускает показывать критерии. */
            String credibilityBasis,
            /** Самостоятельное свидетельство; {@code false} у перепечаток и личных площадок. */
            boolean independent) {}
}
