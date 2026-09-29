package dev.horizon.trends.adapter.web.dto;

import java.time.Instant;
import java.util.List;

import dev.horizon.trends.domain.direction.UnrecognizedDirection;

/**
 * Строка очереди пополнения словаря направлений.
 *
 * <p>{@code hadAWayOut} отделяет две разные задачи, которые иначе выглядят одинаково. Формулировка,
 * от которой до известной статьи один щелчок, стоит куратору минуты. Формулировка, на которую
 * система двадцать раз не смогла предложить ничего, — это направление, о котором продукт не знает
 * вовсе, и её надо разбирать первой.
 */
public record UnrecognizedDirectionView(
        String query,
        String normalizedQuery,
        int occurrences,
        List<String> suggestions,
        boolean hadAWayOut,
        Instant firstSeen,
        Instant lastSeen) {

    public static UnrecognizedDirectionView of(UnrecognizedDirection direction) {
        return new UnrecognizedDirectionView(
                direction.rawQuery(),
                direction.normalizedQuery(),
                direction.occurrences(),
                direction.suggestions(),
                direction.hadAWayOut(),
                direction.firstSeen(),
                direction.lastSeen());
    }
}
