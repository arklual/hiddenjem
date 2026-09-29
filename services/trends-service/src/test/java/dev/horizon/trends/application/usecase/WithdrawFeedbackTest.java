package dev.horizon.trends.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.horizon.platform.common.error.HorizonException;
import dev.horizon.trends.application.port.TrendFeedbackRepository;
import dev.horizon.trends.domain.feedback.TrendFeedback;
import dev.horizon.trends.domain.report.TrendReportId;
import dev.horizon.trends.domain.research.ReportViewer;
import dev.horizon.trends.support.Fixtures;

/**
 * Снятие пометки — отмена действия, у которого появились последствия.
 *
 * <p>Пока пометка «не технология» ни на что не влияла, её необратимость была незаметна: тема
 * оставалась на экране. Как только движок начал убирать помеченные темы до отбора в ТОП-N, проверка
 * «тема есть в отчёте», охраняющая простановку вердикта, превратилась в западню: отменить нельзя
 * ровно то, что сработало, — темы в отчёте больше нет.
 *
 * <p>Поэтому здесь проверяется не принадлежность отчёту, а наличие самой пометки. Снять можно то,
 * что аналитик поставил; ключи, которых он не помечал, по-прежнему отвергаются — иначе появился бы
 * способ писать в разметку произвольные строки.
 */
class WithdrawFeedbackTest {

    private final Recording feedback = new Recording();

    private RecordTrendFeedbackUseCase useCase() {
        var reports = mock(GetTrendReportUseCase.class);
        when(reports.get(any(), any())).thenReturn(Fixtures.report());
        return new RecordTrendFeedbackUseCase(
                feedback, reports, event -> {}, Clock.fixed(Fixtures.NOW, ZoneOffset.UTC));
    }

    private static TrendFeedback mark(String trendKey) {
        return TrendFeedback.record(
                Fixtures.USER_ID,
                new TrendReportId(UUID.randomUUID()),
                trendKey,
                TrendFeedback.Verdict.NOISE,
                null,
                Fixtures.NOW);
    }

    @Test
    void aMarkOnATopicMissingFromTheReportCanStillBeWithdrawn() {
        // Ровно тот случай, ради которого всё: тема скрыта, значит её в отчёте нет, значит прежняя
        // проверка «тема есть в отчёте» отменить бы не дала.
        feedback.existing.add(mark("retriev passage"));

        useCase().withdraw(ReportViewer.of(Fixtures.USER_ID), Fixtures.report().id(), "retriev passage");

        assertThat(feedback.deleted).containsExactly("retriev passage");
    }

    @Test
    void aKeyTheAnalystNeverMarkedIsRejected() {
        // Иначе появился бы способ писать в чужую разметку произвольные строки: ключ приходит из
        // адреса запроса, а не из отчёта.
        feedback.existing.add(mark("retriev passage"));

        assertThatThrownBy(() -> useCase()
                        .withdraw(
                                ReportViewer.of(Fixtures.USER_ID),
                                Fixtures.report().id(),
                                "чужой ключ"))
                .isInstanceOf(HorizonException.class);
        assertThat(feedback.deleted).isEmpty();
    }

    private static final class Recording implements TrendFeedbackRepository {
        private final List<TrendFeedback> existing = new ArrayList<>();
        private final List<String> deleted = new ArrayList<>();

        @Override
        public Optional<TrendFeedback> find(UUID userId, TrendReportId reportId, String trendKey) {
            return Optional.empty();
        }

        @Override
        public List<TrendFeedback> findByUserAndReport(UUID userId, TrendReportId reportId) {
            return List.of();
        }

        @Override
        public List<TrendFeedback> findCarried(UUID userId, String normalizedQuery, TrendReportId excluding) {
            return List.of();
        }

        @Override
        public List<TrendFeedback> findByDirection(UUID userId, String normalizedQuery) {
            return List.copyOf(existing);
        }

        @Override
        public int deleteByDirection(UUID userId, String normalizedQuery, String trendKey) {
            deleted.add(trendKey);
            return 1;
        }

        @Override
        public TrendFeedback save(TrendFeedback entry) {
            return entry;
        }
    }
}
