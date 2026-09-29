package dev.horizon.trends.domain.research;

/**
 * Каким движком посчитан отчёт.
 *
 * <p>Выбирать движок больше нельзя: движок методологии выведен из продукта, и считает один —
 * {@link #SIGNALS}, скоринг по внешним признакам поверх общего конвейера (отбор корпуса, извлечение,
 * индикаторы). Перечисление осталось ради подписи готовых отчётов: всё, что выпущено раньше, посчитано
 * методологией, отчёт неизменяем, и переподписать его задним числом значило бы солгать о прошлом.
 *
 * <p>Имена на проводе — строчные, в точности как в {@code contracts/schemas/analyze-domain.command.json}.
 * Перечисление хранит их явно, а не выводит из {@code name().toLowerCase()}: переименование константы
 * Java не должно молча менять контракт.
 */
public enum AnalysisEngine {
    /** Выведенный из продукта движок; встречается только в подписи старых отчётов. */
    METHODOLOGY("methodology"),
    SIGNALS("signals");

    /** Единственный движок, которым считаются новые отчёты. */
    public static final AnalysisEngine CURRENT = SIGNALS;

    /**
     * Чем посчитан отчёт, не назвавший движка.
     *
     * <p>Не {@link #CURRENT}: подписи нет только у отчётов, выпущенных до появления второго движка, а
     * их считала методология. Это утверждение о прошлом, и с выводом движка из продукта оно не меняется.
     */
    public static final AnalysisEngine UNLABELLED_REPORT = METHODOLOGY;

    private final String wireName;

    AnalysisEngine(String wireName) {
        this.wireName = wireName;
    }

    /** Имя, которым движок называется в команде анализа и в отчёте. */
    public String wireName() {
        return wireName;
    }
}
