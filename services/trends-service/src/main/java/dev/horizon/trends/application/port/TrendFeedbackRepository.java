package dev.horizon.trends.application.port;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import dev.horizon.trends.domain.feedback.TrendFeedback;
import dev.horizon.trends.domain.report.TrendReportId;

public interface TrendFeedbackRepository {

    Optional<TrendFeedback> find(UUID userId, TrendReportId reportId, String trendKey);

    List<TrendFeedback> findByUserAndReport(UUID userId, TrendReportId reportId);

    /**
     * The analyst's last verdict on each topic of the same direction, from any report but this one
     * (BR-A32).
     *
     * <p>Feedback is keyed by report, and a recomputation makes a new report — so without this the
     * markup an analyst spends twenty minutes on disappears every quarter, and after the third
     * quarter they stop making it. "The same direction" is the normalised query, the same definition
     * the freshness lookup and the duplicate guard already use.
     */
    List<TrendFeedback> findCarried(UUID userId, String normalizedQuery, TrendReportId excludingReportId);

    /**
     * Последний вердикт аналитика по каждой теме этого направления — без привязки к отчёту.
     *
     * <p>Нужен в момент сборки команды анализа: отчёта ещё нет, исключать нечего.
     */
    List<TrendFeedback> findByDirection(UUID userId, String normalizedQuery);

    TrendFeedback save(TrendFeedback feedback);

    /**
     * Снять пометку аналитика с темы этого направления.
     *
     * <p>Отдельная операция, а не вердикт «полезно»: аналитик, скрывший тему по ошибке, имел в виду
     * «я ошибся», а не «эта тема полезна». Заставлять его сделать утверждение, которого он не делал,
     * ради отмены собственного действия — плохая сделка: следующий отчёт унаследует это утверждение.
     *
     * @return сколько пометок снято; ноль означает, что снимать было нечего
     */
    int deleteByDirection(UUID userId, String normalizedQuery, String trendKey);
}
