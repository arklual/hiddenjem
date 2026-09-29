package dev.horizon.trends.application.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import dev.horizon.trends.domain.research.AnalysisEngine;

/**
 * Inbound contract from the analytics engine — mirrors {@code contracts/schemas/domain-analyzed.event.json}.
 *
 * <p>This is the anti-corruption boundary between the Python engine and this bounded context
 * (ADR-0016). Keeping a dedicated DTO rather than deserialising straight into domain types means the
 * engine's wire format can evolve without dragging the domain model with it, and every field is
 * validated by {@link dev.horizon.trends.application.usecase.ReportAssembler} before it becomes part
 * of an immutable report.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} is deliberate: additive changes on the
 * producer side must not break consumers (forward compatibility within a topic major version).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalysisResult(
        String researchRequestId,
        int attempt,
        String snapshotId,
        String engine,
        String methodologyVersion,
        String profileId,
        String aggregator,
        String embeddingModelId,
        int documentsAnalyzed,
        int candidatesEvaluated,
        boolean truncated,
        Integer suppressedByAnalyst,
        Boolean directionRecognized,
        List<String> directionSuggestions,
        LocalDate windowFrom,
        LocalDate windowTo,
        Map<String, Double> stageTimingsMs,
        Integer reweightingScenarios,
        List<ExclusionDto> exclusions,
        List<AnalyzedTrend> trends) {

    /**
     * Событие без перечня исключений.
     *
     * <p>Перегрузка ради вызывающих, которых добавочное поле не касается. Jackson по-прежнему
     * читает каноническую форму: у неё другое число компонентов, и выбор создателя однозначен.
     */
    public AnalysisResult(
            String researchRequestId,
            int attempt,
            String snapshotId,
            String methodologyVersion,
            String profileId,
            String aggregator,
            String embeddingModelId,
            int documentsAnalyzed,
            int candidatesEvaluated,
            boolean truncated,
            Integer suppressedByAnalyst,
            Boolean directionRecognized,
            List<String> directionSuggestions,
            LocalDate windowFrom,
            LocalDate windowTo,
            Map<String, Double> stageTimingsMs,
            Integer reweightingScenarios,
            List<AnalyzedTrend> trends) {
        this(
                researchRequestId,
                attempt,
                snapshotId,
                null,
                methodologyVersion,
                profileId,
                aggregator,
                embeddingModelId,
                documentsAnalyzed,
                candidatesEvaluated,
                truncated,
                suppressedByAnalyst,
                directionRecognized,
                directionSuggestions,
                windowFrom,
                windowTo,
                stageTimingsMs,
                reweightingScenarios,
                List.of(),
                trends);
    }

    /**
     * Whether the engine understood which direction it was asked about.
     *
     * <p>Boxed on purpose. The field is additive, and an event produced before it existed omits it;
     * a primitive {@code boolean} would then read as {@code false} and mark every historical report
     * as "direction not recognised". A caveat that fires on reports it does not apply to devalues
     * the ones where it does, so absence reads as "recognised".
     */
    /**
     * Сколько тем скрыто по пометке аналитика; ноль, если движок поле не прислал.
     *
     * <p>Обёртка над {@code Integer}, а не {@code int}: события, выпущенные до появления пометок,
     * поля не несут, и отсутствие обязано читаться как «скрытого не было», а не как ошибка.
     */
    public int suppressedByAnalystOrZero() {
        return suppressedByAnalyst == null ? 0 : suppressedByAnalyst;
    }

    /**
     * Чем посчитан отчёт; {@code methodology}, если движок поля не прислал.
     *
     * <p>Отсутствие — «событие выпущено до появления второго движка», и других посчитанных тогда не
     * было. Незнакомое имя здесь не отвергается: движок вправе быть новее этого сервиса, а
     * единственное, что сервис с этим полем делает, — сохраняет и показывает.
     */
    public String engineOrDefault() {
        return engine == null || engine.isBlank() ? AnalysisEngine.UNLABELLED_REPORT.wireName() : engine;
    }

    /**
     * Сколько наборов весов перебрано при проверке устойчивости места (методология §16).
     *
     * <p>Ноль означает «не измеряли», а не «состав неустойчив»: события, выпущенные до появления
     * проверки, поля не несут, и объявлять их результаты шаткими было бы неправдой.
     */
    public int reweightingScenariosOrZero() {
        return reweightingScenarios == null ? 0 : reweightingScenarios;
    }

    public boolean directionRecognizedOrTrue() {
        return directionRecognized == null || directionRecognized;
    }

    /** Suggestions, or an empty list when the engine sent none. */
    public List<String> directionSuggestionsOrEmpty() {
        return directionSuggestions == null ? List.of() : List.copyOf(directionSuggestions);
    }

    /**
     * Причины исключения кандидатов, или пустой список, если движок их не присылал.
     *
     * <p>Отсутствие означает «движок старый», а не «ничего не отбрасывалось»: отчёт без этого
     * блока честнее пустого блока, который читался бы как «отсеяно ноль».
     */
    public List<ExclusionDto> exclusionsOrEmpty() {
        return exclusions == null ? List.of() : List.copyOf(exclusions);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AnalyzedTrend(
            int rank,
            RankStabilityDto rankStability,
            Double directionShare,
            String trendKey,
            String title,
            String definition,
            List<String> aliases,
            MotivationDto motivation,
            CaseExampleDto caseExample,
            double score,
            double confidence,
            boolean lowEvidence,
            List<IndicatorDto> indicators,
            String lifecycleStage,
            int firstMentionYear,
            int totalDocuments,
            BurstDto burst,
            List<TimelinePointDto> timeline,
            List<EvidenceDto> evidence,
            Boolean lowCredibilityOnly,
            LocalizationDto localization,
            List<ExplanationDto> explanation) {

        /** Тема без объяснения — событие движка до его появления. */
        public AnalyzedTrend(
                int rank,
                RankStabilityDto rankStability,
                Double directionShare,
                String trendKey,
                String title,
                String definition,
                List<String> aliases,
                MotivationDto motivation,
                CaseExampleDto caseExample,
                double score,
                double confidence,
                boolean lowEvidence,
                List<IndicatorDto> indicators,
                String lifecycleStage,
                int firstMentionYear,
                int totalDocuments,
                BurstDto burst,
                List<TimelinePointDto> timeline,
                List<EvidenceDto> evidence,
                Boolean lowCredibilityOnly,
                LocalizationDto localization) {
            this(
                    rank,
                    rankStability,
                    directionShare,
                    trendKey,
                    title,
                    definition,
                    aliases,
                    motivation,
                    caseExample,
                    score,
                    confidence,
                    lowEvidence,
                    indicators,
                    lifecycleStage,
                    firstMentionYear,
                    totalDocuments,
                    burst,
                    timeline,
                    evidence,
                    lowCredibilityOnly,
                    localization,
                    null);
        }

        /** Тема без отметки о доверенности и без русского слоя. */
        public AnalyzedTrend(
                int rank,
                RankStabilityDto rankStability,
                Double directionShare,
                String trendKey,
                String title,
                String definition,
                List<String> aliases,
                MotivationDto motivation,
                CaseExampleDto caseExample,
                double score,
                double confidence,
                boolean lowEvidence,
                List<IndicatorDto> indicators,
                String lifecycleStage,
                int firstMentionYear,
                int totalDocuments,
                BurstDto burst,
                List<TimelinePointDto> timeline,
                List<EvidenceDto> evidence) {
            this(
                    rank,
                    rankStability,
                    directionShare,
                    trendKey,
                    title,
                    definition,
                    aliases,
                    motivation,
                    caseExample,
                    score,
                    confidence,
                    lowEvidence,
                    indicators,
                    lifecycleStage,
                    firstMentionYear,
                    totalDocuments,
                    burst,
                    timeline,
                    evidence,
                    false,
                    null);
        }

        /**
         * Все источники темы низкой доверенности; {@code false}, если движок поле не прислал.
         *
         * <p>Обёртка, а не {@code boolean}: отметка, срабатывающая на отчётах, к которым она не
         * относится, обесценивает те, где она по делу.
         */
        public boolean lowCredibilityOnlyOrFalse() {
            return lowCredibilityOnly != null && lowCredibilityOnly;
        }

        /** Строки объяснения или пустой список, если движок их не прислал. */
        public List<ExplanationDto> explanationOrEmpty() {
            return explanation == null ? List.of() : List.copyOf(explanation);
        }
    }

    /** Строка объяснения темы: заголовок и пояснение. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExplanationDto(String title, String text) {}

    /**
     * Причина исключения с числом и примерами имён.
     *
     * <p>ТЗ требует показывать логику исключения зрелых трендов, стандартов, хайпа и шума. Число
     * без имён проверить нельзя, поэтому примеры приходят вместе со счётчиком.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExclusionDto(String code, String reason, int count, List<String> examples) {}

    /**
     * Русский слой карточки (ADR-0017): перевод рядом с оригиналом, а не вместо него.
     *
     * <p>Имя модели и вид обработки — не украшение, а требование ТЗ: «при использовании
     * автоматического перевода или генеративного резюме это должно быть отмечено возле
     * источника», плюс §3.1 об обязательном раскрытии модели для конкретного ответа.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LocalizationDto(
            String title,
            String titleModel,
            String titleMode,
            String definition,
            String problem,
            String benefit,
            List<String> evidenceTitles,
            String textModel,
            String textMode,
            String statement,
            String statementModel) {}

    /** Лучшее и худшее место темы при изменении весов; {@code null}, если проверка не проводилась. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RankStabilityDto(int best, int worst) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IndicatorDto(
            String name,
            double value,
            double weight,
            double multiplier,
            double shortfallShare,
            String explanation,
            Map<String, Object> diagnostics) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MotivationDto(String problem, String benefit, List<AttributionDto> attributions) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AttributionDto(String statement, int evidenceIndex, String sentence) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CaseExampleDto(
            String organization,
            String organizationType,
            String country,
            String summary,
            int evidenceIndex,
            String basis) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BurstDto(String startPeriod, Double weight) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TimelinePointDto(String period, int documentCount, Double dov, Double dod) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EvidenceDto(
            String sourceId,
            String sourceClass,
            String externalId,
            String documentId,
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
            String language,
            String credibility,
            String credibilityBasis,
            Boolean independent) {

        /** Источник без языка и уровня доверенности. */
        public EvidenceDto(
                String sourceId,
                String sourceClass,
                String externalId,
                String documentId,
                String title,
                String authors,
                String organization,
                String organizationCountry,
                LocalDate publishedOn,
                String url,
                String doi,
                Integer citationCount,
                double relevance,
                String snippet) {
            this(
                    sourceId,
                    sourceClass,
                    externalId,
                    documentId,
                    title,
                    authors,
                    organization,
                    organizationCountry,
                    publishedOn,
                    url,
                    doi,
                    citationCount,
                    relevance,
                    snippet,
                    null,
                    "MEDIUM",
                    null,
                    true);
        }

        /**
         * Уровень доверенности; «средний», если движок поле не прислал.
         *
         * <p>Умолчание именно средним, а не высоким и не низким: событие без поля не даёт
         * оснований ни поручиться за источник, ни усомниться в нём.
         */
        public String credibilityOrDefault() {
            return credibility == null || credibility.isBlank() ? "MEDIUM" : credibility;
        }

        /** Самостоятельное ли свидетельство; {@code true}, если движок поле не прислал. */
        public boolean independentOrTrue() {
            return independent == null || independent;
        }
    }
}
