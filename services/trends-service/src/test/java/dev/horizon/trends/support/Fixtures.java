package dev.horizon.trends.support;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.horizon.trends.application.dto.AnalysisResult;
import dev.horizon.trends.domain.report.Burst;
import dev.horizon.trends.domain.report.CaseExample;
import dev.horizon.trends.domain.report.Coverage;
import dev.horizon.trends.domain.report.EmergenceAssessment;
import dev.horizon.trends.domain.report.Evidence;
import dev.horizon.trends.domain.report.IndicatorScore;
import dev.horizon.trends.domain.report.LifecycleStage;
import dev.horizon.trends.domain.report.MethodologyRef;
import dev.horizon.trends.domain.report.Motivation;
import dev.horizon.trends.domain.report.RankedTrend;
import dev.horizon.trends.domain.report.SourceClass;
import dev.horizon.trends.domain.report.TimelinePoint;
import dev.horizon.trends.domain.report.TrendReport;
import dev.horizon.trends.domain.research.AnalysisParameters;
import dev.horizon.trends.domain.research.RequesterRef;
import dev.horizon.trends.domain.research.ResearchRequest;
import dev.horizon.trends.domain.research.TechnologyDomainQuery;

/**
 * Valid domain objects for tests.
 *
 * <p>Every factory returns something the aggregates actually accept — indicator weights that sum to
 * one, non-empty evidence, contiguous ranks. Building those by hand in each test would bury the
 * assertion under twenty lines of setup and, worse, tempt authors to weaken invariants to make the
 * setup shorter.
 */
public final class Fixtures {

    public static final UUID USER_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    public static final UUID ORGANIZATION_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    public static final UUID PROFILE_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    public static final UUID SNAPSHOT_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    public static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");

    private Fixtures() {}

    public static RequesterRef requester() {
        return new RequesterRef(USER_ID, ORGANIZATION_ID);
    }

    public static TechnologyDomainQuery query() {
        return TechnologyDomainQuery.of("квантовые вычисления");
    }

    public static AnalysisParameters parameters() {
        return AnalysisParameters.defaults(PROFILE_ID);
    }

    public static ResearchRequest pendingRequest() {
        return ResearchRequest.submit(requester(), query(), parameters(), "idem-key-1", Duration.ofMinutes(10), NOW);
    }

    /** A request driven through the saga up to the point where a report may be attached. */
    public static ResearchRequest assemblingRequest() {
        var request = pendingRequest();
        request.startCollecting(NOW.plusSeconds(1));
        request.corpusCollected(SNAPSHOT_ID, corpusCoverage(), NOW.plusSeconds(2));
        request.startAssembling(UUID.randomUUID(), NOW.plusSeconds(3));
        return request;
    }

    public static dev.horizon.trends.domain.research.CorpusCoverage corpusCoverage() {
        return new dev.horizon.trends.domain.research.CorpusCoverage(120, List.of("arxiv", "openalex"), List.of());
    }

    public static List<IndicatorScore> indicators() {
        return List.of(
                new IndicatorScore("novelty", 0.8, 0.20, 0.956, 0.10, "Тема появилась недавно", Map.of("age", 2)),
                new IndicatorScore("growth", 0.7, 0.30, 0.898, 0.30, "Публикации растут", Map.of("slope", 0.9)),
                new IndicatorScore("diffusion", 0.6, 0.15, 0.926, 0.20, "Несколько организаций", Map.of()),
                new IndicatorScore("weakness", 0.9, 0.15, 0.984, 0.05, "Сигнал ещё слабый", Map.of()),
                new IndicatorScore("coherence", 0.75, 0.10, 0.971, 0.15, "Устойчивое словосочетание", Map.of()),
                new IndicatorScore("impact", 0.5, 0.10, 0.933, 0.20, "Есть патенты", Map.of()));
    }

    public static Evidence evidence(String suffix) {
        return new Evidence(
                "arxiv",
                SourceClass.PREPRINT,
                "2403." + suffix,
                "Superconducting qubit coherence " + suffix,
                "Иванов И.И.; Smith J.",
                "МФТИ",
                "RU",
                LocalDate.of(2025, 6, 1),
                "https://arxiv.org/abs/2403." + suffix,
                "10.1000/" + suffix,
                42,
                0.87,
                "Фрагмент текста источника");
    }

    public static RankedTrend trend(int rank, String key) {
        return new RankedTrend(
                rank,
                key,
                "Тренд " + key,
                "Определение тренда " + key,
                new Motivation(
                        "Существующие кубиты теряют когерентность",
                        "Рост времени когерентности на порядок",
                        List.of(new Motivation.Attribution("problem", 0, "Проблема"))),
                new CaseExample(
                        "МФТИ",
                        CaseExample.OrganizationType.UNIVERSITY,
                        "RU",
                        "Прототип на 12 кубитов",
                        0,
                        CaseExample.Basis.ACADEMIC_GROUP),
                new EmergenceAssessment(72.5, 0.81, false, indicators()),
                LifecycleStage.EMERGING,
                2022,
                57,
                new Burst("2024", 3.1),
                List.of(new TimelinePoint("2023", 12, 0.4, 0.2), new TimelinePoint("2024", 31, 0.7, 0.5)),
                List.of(evidence("0001"), evidence("0002")));
    }

    /** Та же тема на другой стадии жизненного цикла — для проверок, где стадия и есть предмет. */
    public static RankedTrend withLifecycleStage(RankedTrend source, LifecycleStage stage) {
        return new RankedTrend(
                source.rank(),
                source.trendKey(),
                source.title(),
                source.definition(),
                source.motivation(),
                source.caseExample(),
                source.assessment(),
                stage,
                source.firstMentionYear(),
                source.totalDocuments(),
                source.burst(),
                source.timeline(),
                source.evidence(),
                source.directionShare());
    }

    /**
     * Coverage with the source lists a caller chooses.
     *
     * <p>The default below never contains a separator or a quote, so anything relying on it cannot
     * exercise escaping. Tests that care about the rendered file build their own.
     */
    public static Coverage coverageWithSources(List<String> used, List<String> unavailable) {
        return new Coverage(
                120,
                340,
                used,
                unavailable,
                false,
                true,
                List.of(),
                0,
                false,
                LocalDate.of(2019, 3, 1),
                LocalDate.of(2026, 3, 1));
    }

    public static TrendReport reportWithCoverage(ResearchRequest request, Coverage coverage) {
        return TrendReport.create(
                request.id(),
                requester(),
                request.query(),
                methodology(),
                SNAPSHOT_ID,
                coverage,
                List.of(trend(1, "a")),
                5,
                1,
                null,
                NOW.plusSeconds(90));
    }

    public static Coverage coverage() {
        return new Coverage(
                120,
                340,
                List.of("arxiv", "openalex"),
                List.of(),
                false,
                true,
                List.of(),
                0,
                false,
                LocalDate.of(2019, 3, 1),
                LocalDate.of(2026, 3, 1));
    }

    public static MethodologyRef methodology() {
        return new MethodologyRef("em-1.0.0", PROFILE_ID, "WEIGHTED_GEOMETRIC");
    }

    public static TrendReport report(ResearchRequest request, int trendCount) {
        var trends = new java.util.ArrayList<RankedTrend>();
        for (int i = 1; i <= trendCount; i++) {
            trends.add(trend(i, "trend-" + i));
        }
        return TrendReport.create(
                request.id(),
                requester(),
                request.query(),
                methodology(),
                SNAPSHOT_ID,
                coverage(),
                trends,
                request.parameters().topN(),
                1,
                null,
                NOW.plusSeconds(90));
    }

    /**
     * A report whose trends are exactly {@code keys}, ranked in the order given.
     *
     * <p>Lets a test state the ranking it means rather than deriving it, which is what comparing two
     * versions needs: the difference between the orders *is* the subject under test.
     */
    public static TrendReport reportWithTrends(
            ResearchRequest request, String methodologyVersion, java.util.List<String> keys, double baseScore) {
        var trends = new java.util.ArrayList<RankedTrend>();
        for (int i = 0; i < keys.size(); i++) {
            RankedTrend base = trend(i + 1, keys.get(i));
            trends.add(new RankedTrend(
                    base.rank(),
                    base.trendKey(),
                    base.title(),
                    base.definition(),
                    base.motivation(),
                    base.caseExample(),
                    new EmergenceAssessment(
                            baseScore - i,
                            base.assessment().confidence(),
                            base.assessment().lowEvidence(),
                            base.assessment().indicators()),
                    base.lifecycleStage(),
                    base.firstMentionYear(),
                    base.totalDocuments(),
                    base.burst(),
                    base.timeline(),
                    base.evidence()));
        }
        return TrendReport.create(
                request.id(),
                requester(),
                request.query(),
                new MethodologyRef(methodologyVersion, PROFILE_ID, "WEIGHTED_GEOMETRIC"),
                SNAPSHOT_ID,
                coverage(),
                trends,
                Math.max(trends.size(), 5),
                1,
                null,
                NOW.plusSeconds(90));
    }

    public static TrendReport report() {
        return report(assemblingRequest(), 3);
    }

    // ── Inbound analytics payload ────────────────────────────────────────────────────────────

    public static AnalysisResult.AnalyzedTrend analyzedTrend(int rank, String key) {
        return new AnalysisResult.AnalyzedTrend(
                rank,
                // Движок присылает диапазон на каждом прогоне: тема двигается на место вниз при
                // ином взвешивании. Ноль здесь означал бы «не измеряли», и путь до экрана остался
                // бы непройденным ни одним тестом.
                new AnalysisResult.RankStabilityDto(rank, rank + 1),
                // Доля направления у настоящей темы — около двух третей (методология §12).
                0.67,
                key,
                "Тренд " + key,
                "Определение " + key,
                List.of(),
                new AnalysisResult.MotivationDto(
                        "Проблема",
                        "Преимущество",
                        List.of(new AnalysisResult.AttributionDto("problem", 0, "Проблема"))),
                new AnalysisResult.CaseExampleDto("МФТИ", "UNIVERSITY", "RU", "Кейс", 0, "ACADEMIC_GROUP"),
                72.5,
                0.81,
                false,
                indicators().stream()
                        .map(i -> new AnalysisResult.IndicatorDto(
                                i.name(),
                                i.value(),
                                i.weight(),
                                i.multiplier(),
                                i.shortfallShare(),
                                i.explanation(),
                                i.diagnostics()))
                        .toList(),
                "EMERGING",
                2022,
                57,
                new AnalysisResult.BurstDto("2024", 3.1),
                List.of(new AnalysisResult.TimelinePointDto("2024", 31, 0.7, 0.5)),
                List.of(evidenceDto("0001")));
    }

    public static AnalysisResult.EvidenceDto evidenceDto(String suffix) {
        return new AnalysisResult.EvidenceDto(
                "arxiv",
                "PREPRINT",
                "2403." + suffix,
                null,
                "Superconducting qubit coherence " + suffix,
                "Иванов И.И.",
                "МФТИ",
                "RU",
                LocalDate.of(2025, 6, 1),
                "https://arxiv.org/abs/2403." + suffix,
                null,
                42,
                0.87,
                "Фрагмент");
    }

    public static AnalysisResult analysisResult(ResearchRequest request, List<AnalysisResult.AnalyzedTrend> trends) {
        return new AnalysisResult(
                request.id().toString(),
                1,
                SNAPSHOT_ID.toString(),
                "em-1.0.0",
                PROFILE_ID.toString(),
                "WEIGHTED_GEOMETRIC",
                "tfidf-svd-384-v1",
                120,
                340,
                false,
                0,
                true,
                List.of(),
                LocalDate.of(2019, 3, 1),
                LocalDate.of(2026, 3, 1),
                Map.of("scoring", 120.0),
                14,
                trends);
    }

    /** Результат движка с обрезанным корпусом — предел профиля достигнут. */
    public static AnalysisResult analysisResultTruncated(
            ResearchRequest request, List<AnalysisResult.AnalyzedTrend> trends) {
        return new AnalysisResult(
                request.id().toString(),
                1,
                SNAPSHOT_ID.toString(),
                "em-1.0.0",
                PROFILE_ID.toString(),
                "WEIGHTED_GEOMETRIC",
                "tfidf-svd-384-v1",
                120,
                340,
                true,
                0,
                true,
                List.of(),
                LocalDate.of(2019, 3, 1),
                LocalDate.of(2026, 3, 1),
                Map.of("scoring", 120.0),
                14,
                trends);
    }
}
