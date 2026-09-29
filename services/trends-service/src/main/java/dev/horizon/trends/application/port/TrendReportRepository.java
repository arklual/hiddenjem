package dev.horizon.trends.application.port;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

import dev.horizon.trends.domain.report.TrendReport;
import dev.horizon.trends.domain.report.TrendReportId;
import dev.horizon.trends.domain.research.ResearchRequestId;

public interface TrendReportRepository {

    Optional<TrendReport> findById(TrendReportId id);

    /**
     * The previous report of the same direction (BR-A37).
     *
     * <p>By direction, not by request: every submission creates a new request and a request yields
     * exactly one report, so a lineage keyed by request left every report at version 1 with no
     * predecessor — and the delta, the radar's entered/left counts and the movement axis of the
     * portfolio map could never fire.
     *
     * @param excludingRequestId the request being assembled right now, excluded explicitly rather
     *     than relying on it not yet being complete — that would tie correctness to the order of two
     *     statements in the saga
     */
    Optional<TrendReport> findLatestForDirection(
            String normalizedQuery, String paramsDiscriminator, ResearchRequestId excludingRequestId);

    int nextVersionFor(String normalizedQuery, String paramsDiscriminator);

    /**
     * Человеческие названия тем по их ключам — из отчётов того же направления.
     *
     * <p>Нужны, чтобы показать аналитику, что именно он скрыл. Обратная связь хранит только ключ
     * темы, а ключ стеммирован: «retriev passage» вместо «retrieved passages». Показать аналитику
     * его же пометку в таком виде — значит потребовать от него расшифровки того, что он сам сделал.
     *
     * <p>Название берётся из отчётов, где тема присутствовала: это единственное место, где оно
     * записано. Альтернатива — хранить название в самой пометке — потребовала бы миграции и
     * дублирования, а название темы принадлежит отчёту, а не мнению о ней.
     */
    Map<String, String> titlesByTrendKey(String normalizedQuery, Collection<String> trendKeys);

    TrendReport save(TrendReport report);
}
