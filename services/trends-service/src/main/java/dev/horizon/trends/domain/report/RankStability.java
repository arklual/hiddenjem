package dev.horizon.trends.domain.report;

import dev.horizon.platform.common.util.Guards;

/**
 * Лучшее и худшее место темы при разумных изменениях весов индикаторов (методология §16).
 *
 * <p>Веса шести индикаторов выбраны экспертно. Это законный способ, но всё же выбор, и без этого
 * диапазона утверждение «тема третья в направлении» неотличимо от следствия того, как мы взвесили.
 * Тема, остающаяся в ТОП-N при любом наборе весов, — вывод о направлении; тема, вылетающая при
 * удвоении одного веса, — вывод о настройках, и подавать их одинаково значило бы продавать
 * точность, которой нет.
 *
 * <p>Измеряется место, а не балл: балл нормирован внутри своего корпуса и между направлениями не
 * сравним, а решение принимают по составу списка.
 */
public record RankStability(int best, int worst) {

    public RankStability {
        Guards.requireArgument(best >= 1, "rankStability.best must be positive");
        Guards.requireArgument(worst >= best, "rankStability.worst must not precede best");
    }

    /** Остаётся ли тема в ТОП-N при любом из рассмотренных наборов весов. */
    public boolean holdsInTop(int topN) {
        return worst <= topN;
    }

    /** Двигалась ли тема вообще: неподвижная тема — самое сильное, что можно сказать о месте. */
    public boolean immovable() {
        return best == worst;
    }
}
