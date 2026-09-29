package dev.horizon.trends.config;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Every feature the product can be shipped without — declared here and nowhere else.
 *
 * <p>Final requirements arrive in a month. What survives them, what has to be hidden for a
 * demonstration and what gets enabled in stages is not knowable now, so being able to switch a
 * feature off without a rebuild is insurance against the one thing that is certain to happen.
 *
 * <p>One enum on purpose. A flag declared next to the code it guards is a flag the admin screen
 * cannot show and the coverage test cannot walk — which is to say, a flag nobody knows about until
 * someone needs it switched off during a demonstration.
 *
 * <p>Everything defaults to <em>on</em>: this registry adds a lever, it does not move it. A flag
 * without an explicit default would let a forgotten environment variable disable a feature quietly,
 * and quietly is exactly how that would be discovered.
 *
 * <p>The base scenario — sign in, ask for a direction, read the top fifteen with their evidence — is
 * deliberately absent. It is the brief itself; a switch for it would mean there is no product.
 */
public enum FeatureFlag {
    RADAR("radar", "Радар", "Экран портфеля направлений и сводка по нему", true),
    DIRECTION_PORTRAIT(
            "direction-portrait", "Портрет направления", "Сводка по направлению в отчёте и в экспорте", true),
    REPORT_DELTA("report-delta", "Сравнение версий", "Что изменилось с предыдущей версии отчёта", true),
    TERM_TRACE("term-trace", "Трассировка термина", "Ответ на вопрос, почему темы нет в отчёте", true),
    REPORT_EXPORT("report-export", "Экспорт отчёта", "Выгрузка отчёта запиской, в JSON и в CSV", true),
    TOPIC_SEARCH(
            "topic-search",
            "Поиск по темам",
            "Ответ на вопрос, писали ли мы про эту тему раньше и в каких направлениях",
            true),
    TREND_FEEDBACK("trend-feedback", "Обратная связь", "Оценка тренда аналитиком", true),
    DIRECTION_OVERLAP(
            "direction-overlap",
            "Пересечение направлений",
            "Темы, встречающиеся сразу в нескольких отслеживаемых направлениях",
            true),
    SAVED_DOMAINS("saved-domains", "Сохранённые направления", "Сохранение направления для отслеживания", true);

    private final String key;
    private final String title;
    private final String description;
    private final boolean enabledByDefault;

    FeatureFlag(String key, String title, String description, boolean enabledByDefault) {
        this.key = key;
        this.title = title;
        this.description = description;
        this.enabledByDefault = enabledByDefault;
    }

    public String key() {
        return key;
    }

    public String title() {
        return title;
    }

    /** What exactly disappears — so the decision is made on the effect, not on the name. */
    public String description() {
        return description;
    }

    public boolean enabledByDefault() {
        return enabledByDefault;
    }

    /** Configuration key this flag reads, e.g. {@code horizon.features.report-delta}. */
    public String property() {
        return "horizon.features." + key;
    }

    public static Optional<FeatureFlag> byKey(String key) {
        return Arrays.stream(values()).filter(flag -> flag.key.equals(key)).findFirst();
    }

    public static List<FeatureFlag> all() {
        return List.of(values());
    }
}
