package dev.horizon.trends.domain.research;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import dev.horizon.platform.common.util.Guards;
import dev.horizon.trends.domain.report.SourceClass;

/**
 * Knobs that change what the analysis produces.
 *
 * <p>They are part of the cache identity: two requests with the same query but different parameters
 * are different questions and must not share a report.
 */
public record AnalysisParameters(
        int topN,
        int yearsWindow,
        Set<SourceClass> sourceClasses,
        double minConfidence,
        boolean includeMature,
        /**
         * Профиль весов, которым считать.
         *
         * <p>Не выбирается аналитиком: запрос всегда получает профиль по умолчанию, и поле хранит,
         * какой именно это был, — умолчание может смениться, а считанный отчёт остаётся считанным им.
         */
        UUID methodologyProfileId,
        /**
         * Режим анализа — сколько времени отчёт вправе занять.
         *
         * <p>Здесь, а не отдельным полем запроса, ровно по причине из шапки: режим меняет корпус, а
         * с ним и то, что отчёт содержит. Быстрый ответ не годится тому, кто согласился ждать
         * качественного, — и наоборот.
         */
        AnalysisMode mode) {

    public static final int DEFAULT_TOP_N = 15;
    public static final int MIN_TOP_N = 5;
    public static final int MAX_TOP_N = 50;
    public static final int DEFAULT_YEARS_WINDOW = 7;
    public static final int MIN_YEARS_WINDOW = 3;
    public static final int MAX_YEARS_WINDOW = 15;

    public AnalysisParameters {
        // Отсутствие режима читается как умолчание, а не как ошибка: поле добавлено позже, и
        // вызывающий, который о режимах не знает, обязан получить прежнее поведение.
        mode = mode == null ? AnalysisMode.DEFAULT : mode;
        Guards.requireRange(topN, "topN", MIN_TOP_N, MAX_TOP_N);
        Guards.requireRange(yearsWindow, "yearsWindow", MIN_YEARS_WINDOW, MAX_YEARS_WINDOW);
        Guards.requireRange(minConfidence, "minConfidence", 0.0, 1.0);
        sourceClasses = sourceClasses == null || sourceClasses.isEmpty()
                ? Collections.unmodifiableSet(EnumSet.noneOf(SourceClass.class))
                : Collections.unmodifiableSet(EnumSet.copyOf(sourceClasses));
    }

    /** An empty source-class set means "every enabled source", which is the sensible default. */
    public static AnalysisParameters defaults(UUID profileId) {
        return defaults(profileId, AnalysisMode.DEFAULT);
    }

    /** Те же умолчания в заданном режиме. */
    public static AnalysisParameters defaults(UUID profileId, AnalysisMode mode) {
        return new AnalysisParameters(DEFAULT_TOP_N, DEFAULT_YEARS_WINDOW, Set.of(), 0.0, false, profileId, mode);
    }

    public AnalysisParameters withProfile(UUID profileId) {
        return new AnalysisParameters(topN, yearsWindow, sourceClasses, minConfidence, includeMature, profileId, mode);
    }

    /**
     * Stable identity of this parameter set, used together with the normalised query as the cache
     * key. Deliberately order-independent for {@code sourceClasses} — a set has no order, and two
     * requests differing only in the order the client listed classes are the same question.
     */
    public String cacheDiscriminator() {
        String classes = sourceClasses.stream()
                .map(Enum::name)
                .sorted()
                .reduce((a, b) -> a + "," + b)
                .orElse("*");
        // Режим — часть ключа, а не приписка к нему: быстрый отчёт, выданный как ответ на просьбу о
        // качественном, — это чужой ответ под именем аналитика.
        //
        // Движка в ключе больше нет: он один. Строки, записанные до этого, оканчиваются его именем
        // (`|methodology`, `|signals`), а новые — именем режима, поэтому прежние ключи с новыми не
        // совпадают, и отчёт выведенного движка не переиспользуется как ответ на новый вопрос.
        return "%d|%d|%s|%.3f|%b|%s|%s"
                .formatted(
                        topN,
                        yearsWindow,
                        classes,
                        minConfidence,
                        includeMature,
                        methodologyProfileId,
                        mode.wireName());
    }
}
