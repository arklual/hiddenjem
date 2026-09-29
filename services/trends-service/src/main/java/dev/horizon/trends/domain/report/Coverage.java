package dev.horizon.trends.domain.report;

import java.time.LocalDate;
import java.util.List;

import dev.horizon.platform.common.util.Guards;

/**
 * What the report was actually computed from.
 *
 * <p>Reporting {@code unavailableSources} and {@code partial} honestly is a product requirement, not
 * a nicety: a decision-maker must know when a conclusion rests on incomplete data (BRULE-8).
 *
 * <p>{@code directionRecognized} is the same kind of caveat about a different failure. The other two
 * say "we found less than we wanted"; this one says <em>we did not understand the question</em>. The
 * engine could not place the requested direction in the vocabulary the corpus is classified with, so
 * its selection of what belongs to the direction carries no information — while the report itself is
 * complete, scored and plausible. Of the two mistakes an analyst can make, taking an empty answer for
 * "nothing is happening" costs a re-phrasing; taking this one for an answer costs a committee paper.
 */
public record Coverage(
        int documentsAnalyzed,
        int candidatesEvaluated,
        List<String> sourcesUsed,
        List<String> unavailableSources,
        boolean partial,
        boolean directionRecognized,
        List<String> directionSuggestions,
        int suppressedByAnalyst,
        /**
         * Корпус обрезан пределом профиля до анализа — часть литературы не рассматривалась.
         *
         * <p>Отличается от `partial` и от «тем меньше запрошенных», с которым его легко спутать:
         * `partial` говорит «источник был недоступен», «тем меньше» — «методология столько и
         * нашла», а это поле — «мы смотрели не всё, что есть». Три разные причины неполноты, и
         * читателю записки они говорят разное.
         *
         * <p>Оговорка приходила от движка и терялась: сборщик отчёта её не передавал, а поле с тем
         * же именем в отчёте вычислялось заново и означало другое.
         */
        boolean corpusTruncated,
        /**
         * Почему кандидаты не попали в отчёт: причина, число и несколько имён.
         *
         * <p>Четвёртая оговорка того же рода, что три предыдущие, и отвечает она на вопрос, который
         * ТЗ требует показывать прямо: «причины исключения зрелых технологий или нерелевантных
         * кандидатов». Пустой список означает «движок их не присылал» — старое событие, — а не
         * «ничего не отбрасывалось»: последнего на непустом корпусе не бывает.
         */
        List<Exclusion> exclusions,
        LocalDate windowFrom,
        LocalDate windowTo) {

    public Coverage {
        Guards.requireArgument(documentsAnalyzed >= 0, "coverage.documentsAnalyzed must be non-negative");
        // Отрицательное число скрытых тем означало бы ошибку счёта на стороне движка, а не
        // необычные данные: молча принять его — значит показать аналитику бессмыслицу.
        Guards.requireArgument(suppressedByAnalyst >= 0, "coverage.suppressedByAnalyst must be non-negative");
        sourcesUsed = sourcesUsed == null ? List.of() : List.copyOf(sourcesUsed);
        unavailableSources = unavailableSources == null ? List.of() : List.copyOf(unavailableSources);
        // Подсказка имеет смысл только вместе с оговоркой. Сохранить её при распознанном
        // направлении значило бы предложить аналитику переформулировать удавшийся запрос.
        directionSuggestions =
                directionRecognized || directionSuggestions == null ? List.of() : List.copyOf(directionSuggestions);
        exclusions = exclusions == null ? List.of() : List.copyOf(exclusions);
        Guards.requireNonNull(windowFrom, "coverage.windowFrom");
        Guards.requireNonNull(windowTo, "coverage.windowTo");
        // A run is partial if any source failed, regardless of what the caller claimed.
        partial = partial || !unavailableSources.isEmpty();
    }

    /**
     * Покрытие без перечня исключений.
     *
     * <p>Перегрузка, а не правка десяти мест вызова: перечень исключений — добавочная оговорка, и
     * прежняя форма обязана продолжать значить то же, что значила. Пустой список здесь читается
     * как «причины не переданы», и интерфейс так его и показывает — блока нет, а не «отсеяно
     * ноль».
     */
    public Coverage(
            int documentsAnalyzed,
            int candidatesEvaluated,
            List<String> sourcesUsed,
            List<String> unavailableSources,
            boolean partial,
            boolean directionRecognized,
            List<String> directionSuggestions,
            int suppressedByAnalyst,
            boolean corpusTruncated,
            LocalDate windowFrom,
            LocalDate windowTo) {
        this(
                documentsAnalyzed,
                candidatesEvaluated,
                sourcesUsed,
                unavailableSources,
                partial,
                directionRecognized,
                directionSuggestions,
                suppressedByAnalyst,
                corpusTruncated,
                List.of(),
                windowFrom,
                windowTo);
    }
}
