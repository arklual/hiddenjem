package dev.horizon.trends.adapter.web;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.horizon.platform.spring.caller.CurrentCaller;
import dev.horizon.trends.adapter.web.dto.UnrecognizedDirectionView;
import dev.horizon.trends.application.port.UnrecognizedDirectionJournal;

/**
 * Очередь пополнения перекрёстного словаря направлений.
 *
 * <p>Отвечает на вопрос, на который до сих пор никто не мог ответить: <b>чего словарю не хватает
 * на самом деле</b>. До журнала он пополнялся догадкой — статьи добавлялись по прочтению корпуса, а
 * не по тому, что набирают аналитики, и покрывал он ровно то, о чём подумал автор.
 *
 * <p>Область — организация вызывающего, и берётся она из токена, а не из параметра. Перечень
 * направлений, которые исследует банк, — его повестка; отдать её соседнему банку было бы
 * разглашением, а не удобством. Роль ограничена администратором по той же причине: это внутренняя
 * кухня продукта, а не часть работы аналитика.
 */
@RestController
@RequestMapping("/api/v1/admin/unrecognized-directions")
public class DirectionLexiconController {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final UnrecognizedDirectionJournal journal;
    private final CurrentCaller currentUser;

    public DirectionLexiconController(UnrecognizedDirectionJournal journal, CurrentCaller currentUser) {
        this.journal = journal;
        this.currentUser = currentUser;
    }

    @GetMapping
    public List<UnrecognizedDirectionView> mostAsked(@RequestParam(name = "limit", required = false) Integer limit) {
        var caller = currentUser.require();
        int bounded = Math.min(limit == null || limit <= 0 ? DEFAULT_LIMIT : limit, MAX_LIMIT);
        return journal.mostAsked(caller.organizationId(), bounded).stream()
                .map(UnrecognizedDirectionView::of)
                .toList();
    }
}
