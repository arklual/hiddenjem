package dev.horizon.trends.domain.direction;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import dev.horizon.platform.common.util.Guards;

/**
 * Направление, которого перекрёстный словарь не знает, и сколько раз его спрашивали.
 *
 * <p>Словарь — ручная работа, и до этого журнала он пополнялся догадкой: я добавлял статьи, читая
 * корпус, а не зная, что набирают аналитики. Догадка даёт словарь, который покрывает то, о чём
 * подумал автор, и молчит о том, что спрашивают на самом деле.
 *
 * <p>Журнал превращает пополнение в очередь, отсортированную по спросу, и заодно даёт число, которого
 * не было ни у кого: <b>доля запросов, где мы не поняли вопроса</b>. Без него нельзя ответить, стало
 * ли лучше после очередной правки словаря.
 *
 * <p>Запись хранит и подсказки, показанные в тот момент. Формулировка, которую спрашивали двадцать
 * раз и на которую система ни разу не смогла ничего предложить, — не та же задача, что формулировка,
 * от которой до известной статьи один щелчок.
 *
 * @param occurrences сколько раз спрашивали; ради него всё и заведено
 * @param suggestions что предлагалось в последний раз; пусто — значит выхода не показали вовсе
 */
public record UnrecognizedDirection(
        UUID organizationId,
        String normalizedQuery,
        String rawQuery,
        int occurrences,
        List<String> suggestions,
        Instant firstSeen,
        Instant lastSeen) {

    public UnrecognizedDirection {
        Guards.requireNonNull(organizationId, "unrecognizedDirection.organizationId");
        normalizedQuery = Guards.requireText(normalizedQuery, "unrecognizedDirection.normalizedQuery");
        rawQuery = rawQuery == null || rawQuery.isBlank() ? normalizedQuery : rawQuery.trim();
        Guards.requireArgument(occurrences > 0, "unrecognizedDirection.occurrences must be positive");
        suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
        Guards.requireNonNull(firstSeen, "unrecognizedDirection.firstSeen");
        Guards.requireNonNull(lastSeen, "unrecognizedDirection.lastSeen");
        Guards.requireArgument(
                !lastSeen.isBefore(firstSeen), "unrecognizedDirection.lastSeen must not precede firstSeen");
    }

    /** Первое наблюдение формулировки. */
    public static UnrecognizedDirection firstTime(
            UUID organizationId, String normalizedQuery, String rawQuery, List<String> suggestions, Instant seenAt) {
        return new UnrecognizedDirection(organizationId, normalizedQuery, rawQuery, 1, suggestions, seenAt, seenAt);
    }

    /** Была ли аналитику показана дорога дальше. */
    public boolean hadAWayOut() {
        return !suggestions.isEmpty();
    }
}
