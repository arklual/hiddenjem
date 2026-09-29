package dev.horizon.trends.adapter.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.horizon.trends.application.port.DirectionCrosswalk;
import dev.horizon.trends.application.port.KnownDirections;

/**
 * Направления, которые система умеет соотнести с корпусом.
 *
 * <p>Отвечает на самый трудный вопрос аналитика — что вообще писать в пустое поле. Система знает
 * ответ и раньше его не показывала: аналитик гадал, а неудачная догадка стоила ему полного прогона
 * анализа, который заканчивался оговоркой «направление не распознано».
 *
 * <p>Доступно любому аутентифицированному пользователю: перечень направлений, которые понимает
 * движок, — свойство продукта, а не данные организации. Ничего о том, что исследует конкретный банк,
 * он не сообщает — в отличие от очереди пополнения, которая закрыта администратором.
 */
@RestController
@RequestMapping("/api/v1/directions")
public class DirectionSuggestionController {

    private final KnownDirections directions;
    private final DirectionCrosswalk crosswalk;

    public DirectionSuggestionController(KnownDirections directions, DirectionCrosswalk crosswalk) {
        this.directions = directions;
        this.crosswalk = crosswalk;
    }

    @GetMapping("/known")
    public List<String> known() {
        return directions.list();
    }

    /**
     * Понимает ли движок эту формулировку — до запуска анализа.
     *
     * <p>Тот же вердикт, что стоял в отчёте красной оговоркой, но выданный вовремя. Прежде аналитик
     * узнавал «направление не распознано» после полутора минут ожидания и списанного слота квоты, а
     * узнав — оставался с тем же вопросом, что и до: как тогда написать. Здесь ответ приходит по
     * ходу набора и вместе с ближайшими известными формулировками.
     *
     * <p>Запрет на запуск из этого не делается. Список направлений — подсказка, а не закрытый
     * перечень: продукт обязан принимать формулировки, которых словарь ещё не знает, иначе
     * пополнять его будет неоткуда.
     */
    @GetMapping("/resolve")
    public Resolution resolve(@RequestParam("query") String query) {
        var resolution = crosswalk.resolve(query);
        return new Resolution(resolution.recognized(), resolution.suggestions());
    }

    /** Форма ответа {@code GET /api/v1/directions/resolve}. */
    public record Resolution(boolean recognized, List<String> suggestions) {}
}
