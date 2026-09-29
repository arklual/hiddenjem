package dev.horizon.trends.domain.report;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What changed between two versions of the same report (BR-A12…BR-A15, UC-12).
 *
 * <p>An analyst who runs a direction quarterly reads the second report in a different mode from the
 * first: they are looking for the difference, not for the field. Half the value here is what the
 * delta lets them <em>skip</em>.
 *
 * <p>A pure function of two immutable reports — nothing is stored. Persisting a delta would create
 * data able to disagree with its own source the day the matching rule changes, and immutability is
 * exactly what makes recomputation free and always consistent.
 */
public record ReportDelta(
        TrendReportId previousReportId,
        int previousVersion,
        List<Entry> entered,
        List<Entry> left,
        List<Entry> stayed,
        Unavailable unavailableReason) {

    /** Why no comparison was made. Absent means a comparison was made — possibly an empty one. */
    public enum Unavailable {
        /** This is the first version of the report; there is nothing to compare with. */
        NO_PREVIOUS_VERSION,
        /**
         * Методология изменилась между прогонами: её версия, профиль весов или агрегатор.
         *
         * <p>Refusing is the honest answer: a different methodology is a different scale, and the
         * difference of two scores measured on different scales is a change of ruler presented as a
         * change in the world.
         *
         * <p>Одно значение на три причины намеренно. Читателю сообщается отказ и его основание;
         * какое именно из трёх полей разошлось, видно в самом отчёте — версия, профиль и агрегатор
         * показаны в его заголовке. Три значения перечисления стоили бы правки контракта, вида,
         * словарей и подписей ради различия, которое читатель может увидеть сам.
         */
        METHODOLOGY_CHANGED
    }

    /**
     * One topic's fate between the versions.
     *
     * @param rank position in the version where the topic is present
     * @param rankChange how many places it climbed — {@code previousRank − rank}, so a positive
     *     number reads as "moved up" despite ranks counting downwards. {@code null} unless the topic
     *     is present in both versions
     * @param scoreChange signed change of the emergence score, {@code null} on the same condition
     */
    public record Entry(
            String trendKey, String title, int rank, Integer rankChange, double score, Double scoreChange) {}

    public static ReportDelta unavailable(Unavailable reason) {
        return new ReportDelta(null, 0, List.of(), List.of(), List.of(), reason);
    }

    /**
     * Compare {@code current} against {@code previous}.
     *
     * <p>Topics are matched by {@code trendKey} — the normalised cluster key — and deliberately not
     * by title. A title is the cluster's label, chosen by a frequency weight, and it legitimately
     * changes between versions for the same subject once the corpus grows: "space model" becomes
     * "selective state space model". Matching on it would report a better name as a topic leaving
     * and another arriving — a loud, plausible, entirely false signal.
     *
     * <p>The price is that a cluster which splits or merges shows up as a departure and an arrival
     * even though the subject stayed. That is documented rather than smoothed over: smoothing would
     * take a similarity heuristic that errs silently in both directions.
     */
    public static ReportDelta between(TrendReport current, TrendReport previous) {
        if (previous == null) {
            return unavailable(Unavailable.NO_PREVIOUS_VERSION);
        }
        // Two reports of different directions are two different questions; their top lists have no
        // common subject and their scores no common scale (methodology §12).
        //
        // By direction, not by request. Requests are what a comparison spans: every submission makes
        // a new one, so requiring them to match meant this guard fired on every call and the delta
        // answered "nothing to compare" under every possible state of the system.
        //
        // The access rule this used to carry moved to the caller, where it can be enforced rather
        // than implied — see GetTrendReportUseCase#delta. Leaving it here made a privacy decision
        // look like an arithmetic one, and it took the feature down with it.
        if (!current.query().normalized().equals(previous.query().normalized())) {
            return unavailable(Unavailable.NO_PREVIOUS_VERSION);
        }
        // Сравнивается методология целиком, а не только её версия. Линейку задают версия формул,
        // профиль весов, агрегатор и движок. Версия меняется редко и осознанно; профиль по умолчанию
        // может смениться миграцией, а агрегатор — его поле, и `MIN_BOUND` даёт баллы на шкале, не
        // сравнимой со взвешенным средним. Отчёт выведенного движка методологии с отчётом сигналов
        // не сравним тем более. Режим тоже входит в сравнение, но расходиться не может: предыдущая
        // версия ищется по ключу параметров, а режим — его часть.
        //
        // Довод тот же, что был записан для версии, и он не про версию: разность двух баллов,
        // измеренных разными линейками, — смена линейки, поданная как изменение мира.
        if (!current.methodology().equals(previous.methodology())) {
            return unavailable(Unavailable.METHODOLOGY_CHANGED);
        }

        Map<String, RankedTrend> before = byKey(previous);
        Map<String, RankedTrend> after = byKey(current);

        var entered = new ArrayList<Entry>();
        var stayed = new ArrayList<Entry>();
        for (RankedTrend trend : current.trends()) {
            RankedTrend was = before.get(trend.trendKey());
            if (was == null) {
                entered.add(arrivalOrDeparture(trend));
            } else {
                stayed.add(new Entry(
                        trend.trendKey(),
                        trend.title(),
                        trend.rank(),
                        was.rank() - trend.rank(),
                        trend.assessment().score(),
                        trend.assessment().score() - was.assessment().score()));
            }
        }

        var left = new ArrayList<Entry>();
        for (RankedTrend trend : previous.trends()) {
            if (!after.containsKey(trend.trendKey())) {
                left.add(arrivalOrDeparture(trend));
            }
        }

        entered.sort(Comparator.comparingInt(Entry::rank));
        left.sort(Comparator.comparingInt(Entry::rank));
        // Accelerating topics first; the key closes the order so it cannot depend on iteration.
        stayed.sort(Comparator.comparing(Entry::rankChange, Comparator.reverseOrder())
                .thenComparingInt(Entry::rank)
                .thenComparing(Entry::trendKey));

        return new ReportDelta(
                previous.id(), previous.version(), List.copyOf(entered), List.copyOf(left), List.copyOf(stayed), null);
    }

    private static Entry arrivalOrDeparture(RankedTrend trend) {
        // No change is reported for a topic present in only one version: there is no previous rank
        // to subtract, and inventing zero would read as "held its position".
        return new Entry(
                trend.trendKey(),
                trend.title(),
                trend.rank(),
                null,
                trend.assessment().score(),
                null);
    }

    private static Map<String, RankedTrend> byKey(TrendReport report) {
        var index = new LinkedHashMap<String, RankedTrend>();
        for (RankedTrend trend : report.trends()) {
            index.put(trend.trendKey(), trend);
        }
        return index;
    }

    /** Whether a comparison was actually made. Empty groups with no reason mean "nothing changed". */
    public boolean isAvailable() {
        return unavailableReason == null;
    }

    public Optional<TrendReportId> previousReport() {
        return Optional.ofNullable(previousReportId);
    }
}
