package dev.horizon.trends.adapter.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import dev.horizon.trends.domain.report.TopicOccurrence;

/**
 * {@link Repository}, а не {@code JpaRepository}: наследник давал бы {@code save} и {@code deleteAll}
 * над неизменяемой проекцией — ровно ту запись, от которой она обещает защитить.
 */
public interface TopicSearchJpaRepository extends Repository<ReportTrendRow, ReportTrendRow.Key> {

    /**
     * Темы по фрагменту среди видимых отчётов (BR-A67, BR-A68).
     *
     * <p>Соединение задано явными условиями, а не связями: проекции намеренно не знают друг о друге
     * — связь между ними означала бы, что через одну можно дотянуться до другой и случайно
     * подгрузить полтаблицы.
     *
     * <p>Ищем по названию, определению и формулировке проблемы. Название темы — это извлечённый из
     * корпуса термин, поэтому искомое слово в нём часто отсутствует: «uranium» лежит в проблеме
     * темы «small modular reactor», а не в её заголовке, и поиск только по заголовку отвечал
     * «ничего нет» на вопрос, ответ на который лежит в соседней колонке.
     *
     * <p>Слова запроса соединяются по И, а не сцепляются в одну подстроку: «распад урана» и «уран,
     * распад» — один и тот же вопрос, а подстрочное совпадение находит только первый. Слотов три:
     * незаполненные приходят как {@code null} и условие пропускают — JPQL не умеет предикат
     * переменной длины, а собирать его строками значит потерять проверку компилятором.
     *
     * <p>Порядок здесь нужен не для показа, а для предела: строки отбираются с ограничением, и без
     * порядка «первые 500» означало бы «какие попадутся». Свежие вхождения полезнее старых, поэтому
     * отсечение идёт с хвоста времени. Окончательный порядок задаёт домен.
     */
    @Query(
            """
            select new dev.horizon.trends.domain.report.TopicOccurrence(
                    t.trendKey, t.title, cast(t.id.rank as integer), r.id, r.version,
                    r.rawQuery, r.normalizedQuery, r.generatedAt)
            from ReportTrendRow t, TrendReportRow r, ResearchRequestEntity q
            where r.id = t.id.reportId
              and q.id = r.researchRequestId
              and (lower(t.title) like :needle1 escape '!'
                   or lower(t.definition) like :needle1 escape '!'
                   or lower(t.problemStatement) like :needle1 escape '!')
              and (:needle2 is null
                   or lower(t.title) like :needle2 escape '!'
                   or lower(t.definition) like :needle2 escape '!'
                   or lower(t.problemStatement) like :needle2 escape '!')
              and (:needle3 is null
                   or lower(t.title) like :needle3 escape '!'
                   or lower(t.definition) like :needle3 escape '!'
                   or lower(t.problemStatement) like :needle3 escape '!')
              and (:administrator = true or q.userId = :userId or q.organizationId = :organizationId)
            order by r.generatedAt desc, t.id.rank asc, r.id asc
            """)
    List<TopicOccurrence> findOccurrences(
            @Param("needle1") String needle1,
            @Param("needle2") String needle2,
            @Param("needle3") String needle3,
            @Param("userId") UUID userId,
            @Param("organizationId") UUID organizationId,
            @Param("administrator") boolean administrator,
            Pageable page);
}
