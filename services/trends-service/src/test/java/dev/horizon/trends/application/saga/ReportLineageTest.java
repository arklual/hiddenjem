package dev.horizon.trends.application.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

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
import dev.horizon.trends.support.Fixtures;

/**
 * A report must be chained to the previous report of the same direction (BR-A37, BR-A38).
 *
 * <p>The lineage used to be keyed by research request. Every submission creates a new request, and a
 * request yields exactly one report — so every report was version 1 with no predecessor, always. The
 * delta answered "nothing to compare with" under every possible state of the system, the radar's
 * entered/left counts were zero for every direction, and the movement axis of the portfolio map was
 * a flat line. Three shipped features that could not fire once.
 */
class ReportLineageTest {

    /** Журнал нераспознанных направлений: здесь не проверяется, но конструктор его требует. */
    private final dev.horizon.trends.application.port.UnrecognizedDirectionJournal journal =
            org.mockito.Mockito.mock(dev.horizon.trends.application.port.UnrecognizedDirectionJournal.class);

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
                journal,
                mock(dev.horizon.trends.application.port.TrendFeedbackRepository.class),
                mock(dev.horizon.trends.application.port.ResearchMetrics.class),
                Clock.fixed(Fixtures.NOW, ZoneOffset.UTC));
    }

    private ResearchRequest arrivedAtAnalysis() {
        var request = Fixtures.pendingRequest();
        request.startCollecting(Fixtures.NOW.plusSeconds(1));
        request.corpusCollected(Fixtures.SNAPSHOT_ID, Fixtures.corpusCoverage(), Fixtures.NOW.plusSeconds(2));
        when(requests.findByIdForUpdate(request.id())).thenReturn(Optional.of(request));
        when(reports.save(any())).thenAnswer(call -> call.getArgument(0));
        return request;
    }

    private void assemble(ResearchRequest request) {
        saga().onDomainAnalyzed(
                        Fixtures.analysisResult(request, List.of(Fixtures.analyzedTrend(1, "квантовые сенсоры"))));
    }

    private TrendReport savedReport() {
        var captor = ArgumentCaptor.forClass(TrendReport.class);
        verify(reports).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void theNewReportPointsAtThePreviousOneOfTheSameDirection() {
        var request = arrivedAtAnalysis();
        var earlier = Fixtures.report();
        when(reports.nextVersionFor(anyString(), anyString())).thenReturn(2);
        when(reports.findLatestForDirection(anyString(), anyString(), any())).thenReturn(Optional.of(earlier));

        assemble(request);

        assertThat(savedReport().previousVersionId())
                .as("без ссылки на предшественника дельта не может сработать ни разу")
                .contains(earlier.id());
    }

    @Test
    void theDirectionIsTheSamePairTheFreshnessRuleUses() {
        // P1. The normalised query *and* the parameter discriminator: comparing a top-15 over seven
        // years with a top-10 over three would report a change of parameters as movement in the field.
        var request = arrivedAtAnalysis();
        when(reports.nextVersionFor(anyString(), anyString())).thenReturn(1);
        when(reports.findLatestForDirection(anyString(), anyString(), any())).thenReturn(Optional.empty());

        assemble(request);

        verify(reports)
                .nextVersionFor(
                        request.query().normalized(), request.parameters().cacheDiscriminator());
        verify(reports)
                .findLatestForDirection(
                        request.query().normalized(), request.parameters().cacheDiscriminator(), request.id());
    }

    @Test
    void theRequestBeingAssembledIsExcludedFromItsOwnLineage() {
        // Passed explicitly rather than relying on the request not yet being terminal: that would tie
        // correctness to the order of two statements inside the saga.
        var request = arrivedAtAnalysis();
        when(reports.nextVersionFor(anyString(), anyString())).thenReturn(1);
        when(reports.findLatestForDirection(anyString(), anyString(), any())).thenReturn(Optional.empty());

        assemble(request);

        var excluded = ArgumentCaptor.forClass(dev.horizon.trends.domain.research.ResearchRequestId.class);
        verify(reports).findLatestForDirection(anyString(), anyString(), excluded.capture());
        assertThat(excluded.getValue()).isEqualTo(request.id());
    }

    @Test
    void theFirstReportOfADirectionHasNoPredecessor() {
        var request = arrivedAtAnalysis();
        when(reports.nextVersionFor(anyString(), anyString())).thenReturn(1);
        when(reports.findLatestForDirection(anyString(), anyString(), any())).thenReturn(Optional.empty());

        assemble(request);

        var report = savedReport();
        assertThat(report.previousVersionId()).isEmpty();
        assertThat(report.version()).isOne();
    }

    @Test
    void theVersionNumberComesFromTheDirectionRatherThanTheRequest() {
        // BR-A38. Counted over the request it was always 1, so the history had no order at all.
        var request = arrivedAtAnalysis();
        when(reports.nextVersionFor(anyString(), anyString())).thenReturn(4);
        when(reports.findLatestForDirection(anyString(), anyString(), any())).thenReturn(Optional.empty());

        assemble(request);

        assertThat(savedReport().version()).isEqualTo(4);
    }
}
