package dev.horizon.trends.application.usecase;

import java.time.Clock;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import dev.horizon.platform.common.error.HorizonException;
import dev.horizon.platform.common.event.DomainEventPublisher;
import dev.horizon.trends.application.port.TrendFeedbackRepository;
import dev.horizon.trends.domain.feedback.TrendFeedback;
import dev.horizon.trends.domain.report.TrendReportId;
import dev.horizon.trends.domain.research.ReportViewer;

/**
 * Records an analyst's verdict on a trend (UC-09).
 *
 * <p>Writing feedback requires the right to <em>read</em> the report, and that right is taken from
 * {@link GetTrendReportUseCase} rather than re-derived here. Two copies of a visibility rule are two
 * places for it to drift, and the copy that drifts is always the one nobody remembers exists.
 *
 * <p>Without the check this endpoint leaked more than it wrote: a caller who knew or guessed a
 * report id could tell a real report from a missing one, and a present trend key from an absent one,
 * by the difference between the two 404s — enumerating another team's findings without ever being
 * allowed to open the report.
 */
@Service
public class RecordTrendFeedbackUseCase {

    private final TrendFeedbackRepository feedback;
    private final GetTrendReportUseCase reports;
    private final DomainEventPublisher events;
    private final Clock clock;

    /**
     * Снять пометку с темы этого направления.
     *
     * <p>Существует потому, что пометка «не технология» стала действием с последствиями: движок
     * убирает такую тему до отбора в ТОП-N, и в отчёте её больше нет. Проверка «тема есть в отчёте»,
     * которая охраняет простановку вердикта, для отмены превращается в западню — отменить нельзя
     * ровно то, что сработало. До появления суппрессии этой западни не было: помеченная тема
     * оставалась на экране.
     *
     * <p>Проверяется не принадлежность отчёту, а наличие самой пометки: снять можно только то, что
     * аналитик поставил. Ключи, которых он не помечал, по-прежнему отвергаются — иначе появился бы
     * способ писать в чужую разметку произвольные строки.
     */
    public void withdraw(ReportViewer viewer, TrendReportId reportId, String trendKey) {
        var report = reports.get(reportId, viewer);
        var existing = feedback.findByDirection(viewer.userId(), report.query().normalized()).stream()
                .anyMatch(mark -> mark.trendKey().equals(trendKey));
        if (!existing) {
            throw HorizonException.notFound("Пометка", trendKey);
        }
        feedback.deleteByDirection(viewer.userId(), report.query().normalized(), trendKey);
    }

    public RecordTrendFeedbackUseCase(
            TrendFeedbackRepository feedback, GetTrendReportUseCase reports, DomainEventPublisher events, Clock clock) {
        this.feedback = feedback;
        this.reports = reports;
        this.events = events;
        this.clock = clock;
    }

    @Transactional
    public TrendFeedback record(
            ReportViewer viewer,
            TrendReportId reportId,
            String trendKey,
            TrendFeedback.Verdict verdict,
            String comment) {

        // Refuses with the same "not found" a stranger gets for a report that does not exist, so the
        // response cannot be used to tell the two apart.
        var report = reports.get(reportId, viewer);
        report.findByKey(trendKey).orElseThrow(() -> HorizonException.notFound("Тренд", trendKey));

        // Разметка остаётся личной, хотя отчёт теперь виден всей организации (P5): вердикт «это
        // шум» — суждение конкретного аналитика, и записать его от имени организации значило бы
        // спрятать тему от того, кто её не отвергал.
        var entry = TrendFeedback.record(viewer.userId(), reportId, trendKey, verdict, comment, clock.instant());
        var saved = feedback.save(entry);
        events.publish(List.of(saved));
        return saved;
    }
}
