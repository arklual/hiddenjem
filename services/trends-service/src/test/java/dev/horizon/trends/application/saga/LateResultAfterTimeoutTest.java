package dev.horizon.trends.application.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import dev.horizon.platform.common.event.DomainEventPublisher;
import dev.horizon.trends.application.port.MethodologyProfileRepository;
import dev.horizon.trends.application.port.ProgressBroadcaster;
import dev.horizon.trends.application.port.ReportCache;
import dev.horizon.trends.application.port.ResearchRequestRepository;
import dev.horizon.trends.application.port.SavedDomainRepository;
import dev.horizon.trends.application.port.TrendReportRepository;
import dev.horizon.trends.application.usecase.ReportAssembler;
import dev.horizon.trends.domain.research.FailureInfo;
import dev.horizon.trends.domain.research.ResearchRequest;
import dev.horizon.trends.domain.research.ResearchStatus;
import dev.horizon.trends.support.Fixtures;

/**
 * A result that arrives after the deadline sweep must still become a report.
 *
 * <p>The sweep marks the request failed on a timer, but nothing tells the engine to stop: there is
 * no distributed cancellation in the system by design. So the analysis keeps running, finishes, and
 * hands back an answer — which the saga was then dropping on the floor because the aggregate had
 * already reached a terminal state. The analyst saw a failure and a retry button while the work they
 * were waiting for was, at that very moment, being computed and thrown away.
 */
class LateResultAfterTimeoutTest {

    private final ResearchRequestRepository requests = mock(ResearchRequestRepository.class);
    private final TrendReportRepository reports = mock(TrendReportRepository.class);

    private ResearchSaga saga() {
        return new ResearchSaga(
                requests,
                reports,
                mock(MethodologyProfileRepository.class),
                new ReportAssembler(),
                mock(DomainEventPublisher.class),
                mock(dev.horizon.trends.application.port.CommandSender.class),
                mock(ProgressBroadcaster.class),
                mock(ReportCache.class),
                mock(SavedDomainRepository.class),
                mock(dev.horizon.trends.application.port.UnrecognizedDirectionJournal.class),
                mock(dev.horizon.trends.application.port.TrendFeedbackRepository.class),
                mock(dev.horizon.trends.application.port.ResearchMetrics.class),
                Clock.fixed(Fixtures.NOW, ZoneOffset.UTC));
    }

    /** A request the engine is working on, wired so that a report can be assembled and saved. */
    private ResearchRequest running() {
        var request = Fixtures.pendingRequest();
        request.startCollecting(Fixtures.NOW.plusSeconds(1));
        request.corpusCollected(Fixtures.SNAPSHOT_ID, Fixtures.corpusCoverage(), Fixtures.NOW.plusSeconds(2));
        when(requests.findByIdForUpdate(request.id())).thenReturn(Optional.of(request));
        when(reports.nextVersionFor(anyString(), anyString())).thenReturn(1);
        when(reports.findLatestForDirection(anyString(), anyString(), any())).thenReturn(Optional.empty());
        when(reports.save(any())).thenAnswer(call -> call.getArgument(0));
        return request;
    }

    @Test
    void aResultThatArrivesAfterTheDeadlineSweepStillBecomesAReport() {
        var request = running();
        request.fail(FailureInfo.timeout(), Fixtures.NOW.plusSeconds(600));

        saga().onDomainAnalyzed(
                        Fixtures.analysisResult(request, List.of(Fixtures.analyzedTrend(1, "квантовые сенсоры"))));

        verify(reports).save(any());
        assertThat(request.status())
                .as("доехавший результат завершает запрос, а не остаётся отказом")
                .isEqualTo(ResearchStatus.COMPLETED);
        assertThat(request.reportId()).isPresent();
        assertThat(request.failure()).as("отказ снят: отчёт существует").isEmpty();
    }

    @Test
    void aRequestThatFailedForARealReasonIsNotReopened() {
        // Сборка сломалась, движок отказал, источник недоступен — это не «мы не дождались», и
        // позднее сообщение таких причин не отменяет.
        var request = running();
        request.fail(
                new FailureInfo(FailureInfo.ANALYSIS_FAILED, "Движок вернул ошибку", false),
                Fixtures.NOW.plusSeconds(60));

        saga().onDomainAnalyzed(
                        Fixtures.analysisResult(request, List.of(Fixtures.analyzedTrend(1, "квантовые сенсоры"))));

        verify(reports, never()).save(any());
        assertThat(request.status()).isEqualTo(ResearchStatus.FAILED);
    }

    @Test
    void aRequestCancelledByItsOwnerIsNotReopened() {
        // Отмена — это решение человека. Пришедший следом результат его не пересматривает.
        var request = running();
        request.cancel(Fixtures.NOW.plusSeconds(60));

        saga().onDomainAnalyzed(
                        Fixtures.analysisResult(request, List.of(Fixtures.analyzedTrend(1, "квантовые сенсоры"))));

        verify(reports, never()).save(any());
        assertThat(request.status()).isEqualTo(ResearchStatus.CANCELLED);
    }
}
