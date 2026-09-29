package dev.horizon.trends.domain.report;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * The direction in the few numbers an analyst has to answer for out loud (BR-A16…BR-A18, JTBD-7).
 *
 * <p>The report answers "which fifteen topics". This answers the question asked before it — "what is
 * going on in this field, and how far can I trust it?" — which analysts currently assemble by hand
 * from fifteen cards before walking into an investment committee.
 *
 * <p>Every value here is counted from the report itself. There is deliberately no generated prose:
 * a sentence like "the field is growing rapidly" cannot be checked, and this summary exists to be
 * repeated by someone who will be held to it. The caveats — thin evidence, unavailable sources —
 * are ordinary fields rather than an optional appendix, because a caveat that can be omitted will
 * be omitted precisely when it matters.
 */
public record DirectionPortrait(
        int trendsInReport,
        int candidatesEvaluated,
        int lowEvidenceCount,
        Integer medianFirstMentionYear,
        List<StageCount> byLifecycleStage,
        LocalDate windowFrom,
        LocalDate windowTo,
        List<String> sourcesUsed,
        List<String> unavailableSources,
        boolean partial,
        boolean directionRecognized,
        List<String> directionSuggestions,
        /**
         * Сколько тем убрано по пометке аналитика «не технология» до отбора в ТОП-N.
         *
         * <p>Здесь, а не только в интерфейсе: портрет уходит в выгрузку, а выгрузку несут комитету.
         * Записка, умалчивающая, что часть кандидатов убрана человеческим решением до ранжирования,
         * заявляет большую объективность, чем имеет — и это худший вид умолчания, потому что
         * проверить его читателю нечем.
         *
         * <p>Число — свойство отчёта, а не мнение: оно записано вместе с ним и одинаково для любого
         * читателя. Сами вердикты и комментарии в выгрузку по-прежнему не попадают.
         */
        int suppressedByAnalyst,
        /** Корпус обрезан пределом профиля: часть литературы направления не рассматривалась. */
        boolean corpusTruncated) {

    /** @param stage the stage key as the methodology names it; @param count topics in that stage */
    public record StageCount(String stage, int count) {}

    public static DirectionPortrait of(TrendReport report) {
        var trends = report.trends();
        var coverage = report.coverage();

        int lowEvidence = 0;
        var years = new ArrayList<Integer>(trends.size());
        Map<LifecycleStage, Integer> known = new EnumMap<>(LifecycleStage.class);

        for (RankedTrend trend : trends) {
            if (trend.assessment().lowEvidence()) {
                lowEvidence++;
            }
            years.add(trend.firstMentionYear());
            // No branch for an unrecognised stage: `RankedTrend` rejects a null one, and a value
            // outside the enum never reaches here — the assembler drops that trend when the engine
            // sends a stage this build does not know. Handling it here would be code that cannot
            // run, pretending to a robustness the pipeline does not actually have.
            known.merge(trend.lifecycleStage(), 1, Integer::sum);
        }

        var stages = new ArrayList<StageCount>();
        // Every stage, including the empty ones, in the methodology's own order. A missing
        // "MATURING: 0" row reads as "not counted" rather than "none", and an order that follows the
        // data changes between reports and defeats reading two of them side by side.
        for (LifecycleStage stage : LifecycleStage.values()) {
            stages.add(new StageCount(stage.name(), known.getOrDefault(stage, 0)));
        }
        return new DirectionPortrait(
                trends.size(),
                coverage.candidatesEvaluated(),
                lowEvidence,
                medianYear(years),
                List.copyOf(stages),
                coverage.windowFrom(),
                coverage.windowTo(),
                List.copyOf(coverage.sourcesUsed()),
                List.copyOf(coverage.unavailableSources()),
                coverage.partial(),
                coverage.directionRecognized(),
                coverage.directionSuggestions(),
                coverage.suppressedByAnalyst(),
                coverage.corpusTruncated());
    }

    /**
     * Median first-mention year, or {@code null} for an empty report.
     *
     * <p>Median rather than mean: one topic first mentioned in 1998 would drag a mean by years and
     * describe no actual topic. On an even count the lower of the two middle years wins — a year is
     * discrete, half of one does not exist, and rounding up would age the top of the field.
     */
    private static Integer medianYear(List<Integer> years) {
        if (years.isEmpty()) {
            return null;
        }
        var sorted = new ArrayList<>(years);
        sorted.sort(Integer::compareTo);
        return sorted.get((sorted.size() - 1) / 2);
    }

    /** Share of the report resting on thin evidence, in {@code [0, 1]}; zero for an empty report. */
    public double lowEvidenceShare() {
        return trendsInReport == 0 ? 0.0 : (double) lowEvidenceCount / trendsInReport;
    }
}
