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
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import dev.horizon.platform.common.event.DomainEventPublisher;
import dev.horizon.trends.application.port.MethodologyProfileRepository;
import dev.horizon.trends.application.port.ProgressBroadcaster;
import dev.horizon.trends.application.port.ReportCache;
import dev.horizon.trends.application.port.ResearchRequestRepository;
import dev.horizon.trends.application.port.SavedDomainRepository;
import dev.horizon.trends.application.port.TrendReportRepository;
import dev.horizon.trends.application.usecase.ReportAssembler;
import dev.horizon.trends.domain.report.TrendReport;
import dev.horizon.trends.domain.research.ResearchRequest;
import dev.horizon.trends.domain.saveddomain.SavedDomain;
import dev.horizon.trends.support.Fixtures;

/**
 * A finished analysis must point the tracked direction at the report it produced.
 *
 * <p>Nothing wrote that pointer. It is set to null when a direction is saved and was never written
 * again, so every saved direction reported "nothing has been analysed here yet" no matter how many
 * analyses had run — the radar was a screen that could not fill, and the overlap built on top of it
 * could only ever answer "there is nothing to compare". Both features were shipped and neither
 * worked, because the calculations were tested and the wiring between them was not.
 */
class TrackedDirectionPointerTest {

    /** Журнал нераспознанных направлений: здесь не проверяется, но конструктор его требует. */
    private final dev.horizon.trends.application.port.UnrecognizedDirectionJournal journal =
            org.mockito.Mockito.mock(dev.horizon.trends.application.port.UnrecognizedDirectionJournal.class);

    private final ResearchRequestRepository requests = mock(ResearchRequestRepository.class);
    private final TrendReportRepository reports = mock(TrendReportRepository.class);
    private final SavedDomainRepository savedDomains = mock(SavedDomainRepository.class);
    private final ReportCache cache = mock(ReportCache.class);

    private ResearchSaga saga() {
        return new ResearchSaga(
                requests,
                reports,
                mock(MethodologyProfileRepository.class),
                new ReportAssembler(),
                mock(DomainEventPublisher.class),
                mock(dev.horizon.trends.application.port.CommandSender.class),
                mock(ProgressBroadcaster.class),
                cache,
                savedDomains,
                journal,
                mock(dev.horizon.trends.application.port.TrendFeedbackRepository.class),
                mock(dev.horizon.trends.application.port.ResearchMetrics.class),
                Clock.fixed(Fixtures.NOW, ZoneOffset.UTC));
    }

    /** A request that has reached the point where results may arrive. */
    private ResearchRequest arrivedAtAnalysis() {
        var request = Fixtures.pendingRequest();
        request.startCollecting(Fixtures.NOW.plusSeconds(1));
        request.corpusCollected(Fixtures.SNAPSHOT_ID, Fixtures.corpusCoverage(), Fixtures.NOW.plusSeconds(2));
        when(requests.findByIdForUpdate(request.id())).thenReturn(Optional.of(request));
        when(reports.nextVersionFor(anyString(), anyString())).thenReturn(1);
        when(reports.findLatestForDirection(anyString(), anyString(), any())).thenReturn(Optional.empty());
        when(reports.save(any())).thenAnswer(call -> call.getArgument(0));
        return request;
    }

    private static SavedDomain tracked(UUID userId) {
        return SavedDomain.create(userId, Fixtures.query(), Fixtures.parameters(), Fixtures.NOW);
    }

    @Test
    void theTrackedDirectionEndsUpPointingAtTheNewReport() {
        var request = arrivedAtAnalysis();
        var direction = tracked(Fixtures.USER_ID);
        when(savedDomains.findByUserAndQuery(Fixtures.USER_ID, request.query().normalized()))
                .thenReturn(Optional.of(direction));

        saga().onDomainAnalyzed(
                        Fixtures.analysisResult(request, List.of(Fixtures.analyzedTrend(1, "квантовые сенсоры"))));

        var saved = ArgumentCaptor.forClass(SavedDomain.class);
        verify(savedDomains).save(saved.capture());
        var reportSaved = ArgumentCaptor.forClass(TrendReport.class);
        verify(reports).save(reportSaved.capture());
        assertThat(saved.getValue().lastReport())
                .as("направление должно указывать на только что собранный отчёт")
                .contains(reportSaved.getValue().id());
    }

    @Test
    void aDirectionNobodyTracksIsNotInvented() {
        // The analysis is a legitimate one-off: most questions are asked once and never saved.
        var request = arrivedAtAnalysis();
        when(savedDomains.findByUserAndQuery(any(), anyString())).thenReturn(Optional.empty());

        saga().onDomainAnalyzed(
                        Fixtures.analysisResult(request, List.of(Fixtures.analyzedTrend(1, "квантовые сенсоры"))));

        verify(savedDomains, never()).save(any());
    }

    @Test
    void theDirectionIsLookedUpByTheRequesterAndTheNormalisedQuestion() {
        // Not by raw text, and not across users: an analysis run by someone else must never move an
        // analyst's own bookmark, and "Квантовые Вычисления" is the same direction as what they saved.
        var request = arrivedAtAnalysis();
        when(savedDomains.findByUserAndQuery(any(), anyString())).thenReturn(Optional.empty());

        saga().onDomainAnalyzed(
                        Fixtures.analysisResult(request, List.of(Fixtures.analyzedTrend(1, "квантовые сенсоры"))));

        verify(savedDomains)
                .findByUserAndQuery(
                        request.requester().userId(), request.query().normalized());
    }
}
