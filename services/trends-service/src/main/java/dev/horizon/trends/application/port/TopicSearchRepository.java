package dev.horizon.trends.application.port;

import java.util.List;

import dev.horizon.trends.domain.report.TopicOccurrence;
import dev.horizon.trends.domain.research.ReportViewer;

public interface TopicSearchRepository {

    /**
     * Вхождения тем, чьё название содержит фрагмент, среди отчётов, видимых этому человеку.
     *
     * <p>Граница доступа стоит в запросе, а не поверх него: отфильтровать после выборки значило бы
     * сначала прочитать чужое, а предел строк — применить к чужому тоже, и тогда одна крупная
     * организация вытесняла бы из выдачи всё остальное.
     *
     * @param rowLimit предел по строкам, а не по темам: сколько получится тем, до группировки
     *     неизвестно
     */
    List<TopicOccurrence> findOccurrences(String titleFragment, ReportViewer viewer, int rowLimit);
}
