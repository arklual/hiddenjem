package dev.horizon.ingestion.domain.port;

import java.util.Locale;

/**
 * Режим анализа, для которого идёт сбор: поле {@code mode} команды {@code CollectDomainCorpus}.
 *
 * <p>Сбор не знает, сколько длится анализ целиком, но от режима зависят бюджеты источников — прежде
 * всего глубокого исследования: быстрый анализ укладывается в двадцать минут, и агенту отдано
 * шесть; качественный — в сорок, и агенту отдано двадцать.
 */
public enum AnalysisMode {
    /** Весь анализ за двадцать минут. Умолчание: команда без поля — команда старого вида. */
    FAST,
    /** Весь анализ за сорок минут: глубокое исследование читает втрое больше страниц. */
    QUALITY;

    /**
     * Режим по значению поля контракта.
     *
     * <p>Отсутствие поля — {@link #FAST}: так было до его появления, и отправитель старого вида
     * остаётся совместимым. Неизвестное значение — ошибка команды, а не молчаливый быстрый режим:
     * аналитик, попросивший качество и получивший скорость, не узнал бы об этом ничем.
     *
     * @throws IllegalArgumentException значение задано, но такого режима нет
     */
    public static AnalysisMode parse(String value) {
        if (value == null || value.isBlank()) {
            return FAST;
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "fast" -> FAST;
            case "quality" -> QUALITY;
            default -> throw new IllegalArgumentException("Unknown analysis mode: " + value);
        };
    }
}
