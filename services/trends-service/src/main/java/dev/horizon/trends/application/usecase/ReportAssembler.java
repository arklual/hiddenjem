package dev.horizon.trends.application.usecase;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.horizon.platform.common.error.HorizonException;
import dev.horizon.platform.common.error.ProblemType;
import dev.horizon.trends.application.dto.AnalysisResult;
import dev.horizon.trends.domain.report.Burst;
import dev.horizon.trends.domain.report.CaseExample;
import dev.horizon.trends.domain.report.Coverage;
import dev.horizon.trends.domain.report.Credibility;
import dev.horizon.trends.domain.report.EmergenceAssessment;
import dev.horizon.trends.domain.report.Evidence;
import dev.horizon.trends.domain.report.Exclusion;
import dev.horizon.trends.domain.report.ExplanationItem;
import dev.horizon.trends.domain.report.IndicatorScore;
import dev.horizon.trends.domain.report.LifecycleStage;
import dev.horizon.trends.domain.report.MethodologyRef;
import dev.horizon.trends.domain.report.Motivation;
import dev.horizon.trends.domain.report.RankStability;
import dev.horizon.trends.domain.report.RankedTrend;
import dev.horizon.trends.domain.report.SourceClass;
import dev.horizon.trends.domain.report.TimelinePoint;
import dev.horizon.trends.domain.report.TrendLocalization;
import dev.horizon.trends.domain.report.TrendReport;
import dev.horizon.trends.domain.report.TrendReportId;
import dev.horizon.trends.domain.research.RequesterRef;
import dev.horizon.trends.domain.research.ResearchRequest;

/**
 * Turns the analytics engine's output into an immutable {@link TrendReport}.
 *
 * <p>This is where the system decides what is publishable. Trends that violate a domain invariant
 * (no evidence, dangling attribution index, unknown enum value) are **dropped with a warning rather
 * than failing the whole report**: losing one questionable trend is far better than denying the
 * analyst the other fourteen. Whether anything was dropped is visible through {@code truncated} and
 * the logs, so the degradation is never silent.
 *
 * <p>Ranks are re-numbered after filtering so invariant J1 (contiguous 1..n) always holds.
 */
public class ReportAssembler {

    private static final Logger log = LoggerFactory.getLogger(ReportAssembler.class);

    public TrendReport assemble(
            ResearchRequest request,
            AnalysisResult result,
            List<String> sourcesUsed,
            List<String> unavailableSources,
            int version,
            TrendReportId previousVersionId,
            Instant now) {

        var accepted = new ArrayList<RankedTrend>();
        int nextRank = 1;
        for (var trend : sortedByRank(result.trends())) {
            if (accepted.size() >= request.parameters().topN()) {
                break;
            }
            try {
                accepted.add(toRankedTrend(trend, nextRank));
                nextRank++;
            } catch (RuntimeException e) {
                log.warn("Тренд '{}' исключён из отчёта {}: {}", trend.trendKey(), request.id(), e.getMessage());
            }
        }

        if (accepted.isEmpty()) {
            throw new HorizonException(
                    ProblemType.ANALYSIS_FAILED,
                    "Аналитический движок не вернул ни одного тренда, удовлетворяющего требованиям качества");
        }

        var coverage = new Coverage(
                result.documentsAnalyzed(),
                result.candidatesEvaluated(),
                sourcesUsed,
                unavailableSources,
                request.partial(),
                result.directionRecognizedOrTrue(),
                result.directionSuggestionsOrEmpty(),
                result.suppressedByAnalystOrZero(),
                result.truncated(),
                toExclusions(result.exclusionsOrEmpty()),
                result.windowFrom(),
                result.windowTo());

        var methodology = new MethodologyRef(
                result.methodologyVersion(),
                UUID.fromString(result.profileId()),
                result.aggregator() == null ? "WEIGHTED_GEOMETRIC" : result.aggregator(),
                // Подпись берётся у движка, а не у запроса: запрос говорит, чем просили считать, а
                // отчёт обязан говорить, чем посчитано, — и это ровно то место, где эти два
                // утверждения могут разойтись.
                result.engineOrDefault(),
                // Режим, наоборот, у запроса: движок о нём не знает — режим задаёт бюджет сбора и
                // срок, а не формулу, — и назвать его может только тот, кто его выбрал.
                request.parameters().mode().wireName());

        return TrendReport.create(
                request.id(),
                new RequesterRef(
                        request.requester().userId(), request.requester().organizationId()),
                request.query(),
                methodology,
                UUID.fromString(result.snapshotId()),
                coverage,
                accepted,
                request.parameters().topN(),
                version,
                previousVersionId,
                now);
    }

    private List<AnalysisResult.AnalyzedTrend> sortedByRank(List<AnalysisResult.AnalyzedTrend> trends) {
        if (trends == null) {
            return List.of();
        }
        return trends.stream()
                .sorted((a, b) -> Integer.compare(a.rank(), b.rank()))
                .toList();
    }

    private RankedTrend toRankedTrend(AnalysisResult.AnalyzedTrend dto, int rank) {
        var evidence = dto.evidence() == null
                ? List.<Evidence>of()
                : dto.evidence().stream().map(this::toEvidence).toList();

        var indicators = dto.indicators() == null
                ? List.<IndicatorScore>of()
                : dto.indicators().stream()
                        .map(i -> new IndicatorScore(
                                i.name(),
                                i.value(),
                                i.weight(),
                                i.multiplier(),
                                i.shortfallShare(),
                                i.explanation(),
                                i.diagnostics()))
                        .toList();

        // Диапазон места приходит только от движка: пересчитать его здесь было бы невозможно —
        // веса и агрегатор принадлежат методологии, а не отчёту, и вторая их копия разошлась бы с
        // первой молча.
        //
        // Но измерен он для той нумерации, которую прислал движок. Сборщик выбрасывает
        // непубликуемые темы и перенумеровывает остальные, и тогда диапазон относится к другому
        // порядку: карточка сказала бы «место 2» и рядом «при других весах — с 3-го по 3-е»,
        // противореча себе в двух соседних строках. BR-A35 запрещает именно это, и читатель,
        // поймавший продукт на таком, вправе не поверить обеим строкам.
        //
        // Сдвинувшаяся тема теряет диапазон, несдвинувшиеся сохраняют: выброс обычно один и в
        // середине списка, и терять измерение у всех тем из-за одной было бы дороже, чем нужно.
        // Молчание здесь честно — «не измеряли для этого порядка», — а пересчитать место без
        // выброшенного соседа нельзя: он занимал места и в остальных тринадцати взвешиваниях.
        var stability = dto.rankStability() == null || dto.rank() != rank
                ? null
                : new RankStability(
                        dto.rankStability().best(), dto.rankStability().worst());
        var assessment =
                new EmergenceAssessment(dto.score(), dto.confidence(), dto.lowEvidence(), indicators, stability);

        var motivation = new Motivation(
                dto.motivation().problem(),
                dto.motivation().benefit(),
                dto.motivation().attributions() == null
                        ? List.of()
                        : dto.motivation().attributions().stream()
                                .map(a -> new Motivation.Attribution(a.statement(), a.evidenceIndex(), a.sentence()))
                                .toList());

        CaseExample caseExample = null;
        if (dto.caseExample() != null) {
            caseExample = new CaseExample(
                    dto.caseExample().organization(),
                    parseOrganizationType(dto.caseExample().organizationType()),
                    dto.caseExample().country(),
                    dto.caseExample().summary(),
                    dto.caseExample().evidenceIndex(),
                    parseCaseBasis(dto.caseExample().basis()));
        }

        var timeline = dto.timeline() == null
                ? List.<TimelinePoint>of()
                : dto.timeline().stream()
                        .map(t -> new TimelinePoint(t.period(), t.documentCount(), t.dov(), t.dod()))
                        .toList();

        Burst burst = dto.burst() == null
                ? null
                : new Burst(dto.burst().startPeriod(), dto.burst().weight());

        return new RankedTrend(
                rank,
                dto.trendKey(),
                dto.title(),
                dto.definition(),
                motivation,
                caseExample,
                assessment,
                LifecycleStage.valueOf(dto.lifecycleStage()),
                dto.firstMentionYear(),
                dto.totalDocuments(),
                burst,
                timeline,
                evidence,
                dto.directionShare(),
                dto.lowCredibilityOnlyOrFalse(),
                toLocalization(dto.localization()),
                dto.explanationOrEmpty().stream()
                        .filter(item -> item.title() != null && item.text() != null)
                        .map(item -> new ExplanationItem(item.title(), item.text()))
                        .toList());
    }

    /**
     * Причины исключения из события в домен.
     *
     * <p>Причина с нулевым счётчиком отбрасывается, а не исправляется: она означала бы «отсеяли
     * ноль по этой причине», то есть строку, которая ничего не сообщает и занимает место в
     * перечне, который читают целиком.
     */
    private List<Exclusion> toExclusions(List<AnalysisResult.ExclusionDto> dtos) {
        return dtos.stream()
                .filter(dto -> dto.code() != null && dto.reason() != null && dto.count() >= 1)
                .map(dto -> new Exclusion(dto.code(), dto.reason(), dto.count(), dto.examples()))
                .toList();
    }

    /**
     * Русский слой из события в домен.
     *
     * <p>{@code null} и пустой слой различать незачем: оба означают «моделей не было», и карточка
     * показывает оригинал.
     */
    private TrendLocalization toLocalization(AnalysisResult.LocalizationDto dto) {
        if (dto == null) {
            return TrendLocalization.NONE;
        }
        return new TrendLocalization(
                dto.title(),
                dto.titleModel(),
                dto.titleMode(),
                dto.definition(),
                dto.problem(),
                dto.benefit(),
                dto.evidenceTitles(),
                dto.textModel(),
                dto.textMode(),
                dto.statement(),
                dto.statementModel());
    }

    private Evidence toEvidence(AnalysisResult.EvidenceDto dto) {
        return new Evidence(
                dto.sourceId(),
                SourceClass.valueOf(dto.sourceClass()),
                dto.externalId(),
                dto.title(),
                dto.authors(),
                dto.organization(),
                dto.organizationCountry(),
                dto.publishedOn(),
                dto.url(),
                dto.doi(),
                dto.citationCount(),
                dto.relevance(),
                dto.snippet(),
                dto.language(),
                parseCredibility(dto.credibilityOrDefault()),
                dto.credibilityBasis(),
                dto.independentOrTrue());
    }

    /**
     * Уровень доверенности из события.
     *
     * <p>Неизвестное значение не должно стоить нам источника: незнакомый уровень читается как
     * средний и пишется в лог. Отбросить источник целиком значило бы наказать отчёт за новую
     * версию движка.
     */
    private Credibility parseCredibility(String value) {
        try {
            return Credibility.valueOf(value);
        } catch (IllegalArgumentException e) {
            log.debug("Неизвестный уровень доверенности '{}', принят средний", value);
            return Credibility.MEDIUM;
        }
    }

    private CaseExample.OrganizationType parseOrganizationType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return CaseExample.OrganizationType.valueOf(value);
        } catch (IllegalArgumentException e) {
            // An unknown organisation type must not cost us the whole trend.
            log.debug("Неизвестный тип организации '{}', поле опущено", value);
            return null;
        }
    }

    private CaseExample.Basis parseCaseBasis(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return CaseExample.Basis.valueOf(value);
        } catch (IllegalArgumentException e) {
            // Неизвестное основание не стоит темы целиком: карточка покажется без оговорки о том,
            // на чём держится пример, — это хуже правильной оговорки, но лучше потерянной темы.
            log.debug("Неизвестное основание кейс-примера '{}', поле опущено", value);
            return null;
        }
    }
}
