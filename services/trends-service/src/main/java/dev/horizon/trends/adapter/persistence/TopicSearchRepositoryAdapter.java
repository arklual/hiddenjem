package dev.horizon.trends.adapter.persistence;

import java.util.List;
import java.util.Locale;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import dev.horizon.trends.application.port.TopicSearchRepository;
import dev.horizon.trends.domain.report.TopicOccurrence;
import dev.horizon.trends.domain.research.ReportViewer;

/** Implements {@link TopicSearchRepository} on top of JPA. */
@Repository
public class TopicSearchRepositoryAdapter implements TopicSearchRepository {

    private final TopicSearchJpaRepository jpa;

    public TopicSearchRepositoryAdapter(TopicSearchJpaRepository jpa) {
        this.jpa = jpa;
    }

    @Override
    @Transactional(readOnly = true)
    public List<TopicOccurrence> findOccurrences(String titleFragment, ReportViewer viewer, int rowLimit) {
        var needles = needles(titleFragment);
        return jpa.findOccurrences(
                needles.get(0),
                needles.size() > 1 ? needles.get(1) : null,
                needles.size() > 2 ? needles.get(2) : null,
                viewer.userId(),
                viewer.organizationId(),
                viewer.administrator(),
                PageRequest.of(0, rowLimit));
    }

    /** Сколько слов запроса участвует в отборе: больше — и предикат перестаёт помещаться в план. */
    private static final int MAX_TOKENS = 3;

    /**
     * Слова запроса, каждое — отдельной подстрокой.
     *
     * <p>Порядок слов аналитик не обязан угадывать: «распад урана» и «уран и его распад» — один
     * вопрос. Хвост сверх предела отбрасывается: лишние слова только сузили бы выдачу, а искать по
     * первым трём — честнее, чем не искать вовсе.
     */
    private static List<String> needles(String fragment) {
        var tokens = List.of(fragment.trim().split("\\s+"));
        var limited = tokens.size() > MAX_TOKENS ? tokens.subList(0, MAX_TOKENS) : tokens;
        return limited.stream().map(TopicSearchRepositoryAdapter::needle).toList();
    }

    /**
     * Подстрока без учёта регистра, со снятыми спецсимволами LIKE.
     *
     * <p>Процент и подчёркивание в запросе аналитика — это символы, которые он ищет, а не шаблон,
     * который он задаёт. Оставить их означало бы, что «100_» находит «1000» и «100 %» совпадает со
     * всем корпусом.
     */
    private static String needle(String fragment) {
        var escaped = fragment.toLowerCase(Locale.ROOT)
                .replace("!", "!!")
                .replace("%", "!%")
                .replace("_", "!_");
        return "%" + escaped + "%";
    }
}
