package dev.horizon.trends.domain.report;

import java.util.List;

import dev.horizon.platform.common.util.Guards;

/**
 * Why the trend matters: the problem it addresses and the advantage it brings (BR-A3).
 *
 * <p>{@code attributions} tie each statement to the evidence item it was extracted from. Without
 * them the text would be an unverifiable claim, which the product principles forbid (ADR-0010).
 */
public record Motivation(String problem, String benefit, List<Attribution> attributions) {

    public Motivation {
        Guards.requireText(problem, "motivation.problem");
        Guards.requireText(benefit, "motivation.benefit");
        attributions = attributions == null ? List.of() : List.copyOf(attributions);
    }

    /**
     * @param statement either {@code problem} or {@code benefit}
     * @param sentence то самое предложение, взятое из этого документа; {@code null} — атрибуция,
     *     записанная до появления поля
     *     <p>Формулировка склеивается из предложений разных статей: по эталонному корпусу 166 из
     *     180 собраны из двух и более документов. Без предложения ссылка указывает на абзац
     *     целиком, и читателю остаётся гадать, чья в нём половина, — а в 51 случае вторая половина
     *     говорит «the proposed method» о работе другого автора.
     */
    public record Attribution(String statement, int evidenceIndex, String sentence) {
        public Attribution {
            Guards.requireText(statement, "attribution.statement");
            Guards.requireArgument(evidenceIndex >= 0, "attribution.evidenceIndex must be non-negative");
        }
    }
}
