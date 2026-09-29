package dev.horizon.trends.domain.report;

/** Position on the technology S-curve (methodology §6). */
public enum LifecycleStage {
    EMBRYONIC("Зарождение", "Единичные исследования, тема ещё не оформилась"),
    EMERGING("Становление", "Растущее число публикаций при небольшом общем объёме"),
    ACCELERATING("Ускорение", "Быстрый рост и всплеск внимания сообщества"),
    MATURING("Зрелость", "Рост замедляется, появляются патенты и продукты");

    private final String title;
    private final String description;

    LifecycleStage(String title, String description) {
        this.title = title;
        this.description = description;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }
}
