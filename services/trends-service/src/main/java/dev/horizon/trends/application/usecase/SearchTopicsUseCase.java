package dev.horizon.trends.application.usecase;

import org.springframework.stereotype.Service;

import dev.horizon.platform.common.error.HorizonException;
import dev.horizon.platform.common.error.ProblemType;
import dev.horizon.trends.application.port.TopicSearchRepository;
import dev.horizon.trends.domain.report.TopicSearchResult;
import dev.horizon.trends.domain.research.ReportViewer;

/**
 * «Мы уже это исследовали» (UC-17).
 *
 * <p>Корпус отчётов — самое дорогое, что производит продукт, и до сих пор он был доступен только на
 * запись: узнать, писали ли про тему раньше, было негде. Аналитик тратил слот квоты и три минуты на
 * то, что уже лежит в базе, и — что хуже — не узнавал, что тема поднималась в другом направлении.
 */
@Service
public class SearchTopicsUseCase {

    /** Короче — не поиск: одна буква совпадает почти со всем (P2). */
    static final int MIN_FRAGMENT = 2;

    /**
     * Длиннее названия темы, чем оно бывает: {@code title} — {@code varchar(200)}, поэтому запрос
     * длиннее гарантированно не совпадёт ни с чем. Предел объявлен в контракте, и проверять его
     * обязан сервер: объявленное и не проверенное — это то же самое, что не объявленное.
     */
    static final int MAX_FRAGMENT = 200;

    /** Сколько тем показывается. */
    static final int TOPIC_LIMIT = 25;

    /**
     * Предел по строкам, а не по темам: до группировки неизвестно, сколько получится тем, а запрос
     * по подстроке без предела — это способ выгрузить корпус одним обращением.
     */
    static final int ROW_LIMIT = 500;

    private final TopicSearchRepository topics;

    public SearchTopicsUseCase(TopicSearchRepository topics) {
        this.topics = topics;
    }

    public TopicSearchResult search(String fragment, ReportViewer viewer) {
        var trimmed = fragment == null ? "" : fragment.trim();
        if (trimmed.length() < MIN_FRAGMENT) {
            throw new HorizonException(
                    ProblemType.VALIDATION_ERROR,
                    "Запрос для поиска по темам должен быть не короче %d символов".formatted(MIN_FRAGMENT));
        }
        if (trimmed.length() > MAX_FRAGMENT) {
            throw new HorizonException(
                    ProblemType.VALIDATION_ERROR,
                    "Запрос для поиска по темам должен быть не длиннее %d символов".formatted(MAX_FRAGMENT));
        }
        var occurrences = topics.findOccurrences(trimmed, viewer, ROW_LIMIT);
        // Ровно предел означает «возможно, было больше»: отличить его от «столько и есть» нельзя,
        // и утверждать второе, имея основание лишь для первого, — то же самое, что промолчать.
        return TopicSearchResult.of(occurrences, TOPIC_LIMIT, occurrences.size() >= ROW_LIMIT);
    }
}
