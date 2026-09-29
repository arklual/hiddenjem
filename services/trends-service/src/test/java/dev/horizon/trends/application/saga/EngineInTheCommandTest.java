package dev.horizon.trends.application.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import dev.horizon.platform.common.event.DomainEventPublisher;
import dev.horizon.trends.application.port.CommandSender;
import dev.horizon.trends.application.port.MethodologyProfileRepository;
import dev.horizon.trends.application.port.ProgressBroadcaster;
import dev.horizon.trends.application.port.ReportCache;
import dev.horizon.trends.application.port.ResearchRequestRepository;
import dev.horizon.trends.application.port.SavedDomainRepository;
import dev.horizon.trends.application.port.TrendFeedbackRepository;
import dev.horizon.trends.application.port.TrendReportRepository;
import dev.horizon.trends.application.port.UnrecognizedDirectionJournal;
import dev.horizon.trends.application.usecase.ReportAssembler;
import dev.horizon.trends.domain.methodology.MethodologyProfile;
import dev.horizon.trends.domain.research.AnalysisMode;
import dev.horizon.trends.domain.research.AnalysisParameters;
import dev.horizon.trends.domain.research.ResearchRequest;
import dev.horizon.trends.support.Fixtures;

/**
 * Команда анализа называет движок явно, и это всегда {@code signals}.
 *
 * <p>Движок методологии выведен из продукта. Запросы, записанные до этого, несут {@code methodology}
 * в своей строке, и если бы сага по-прежнему брала имя из запроса, их повтор ушёл бы движку с именем,
 * которого тот больше не считает. Явное имя, а не пропущенное поле: молчание означало бы «возьми своё
 * умолчание», и движок с другим умолчанием посчитал бы не тем, чем записано здесь.
 */
class EngineInTheCommandTest {

    private final ResearchRequestRepository requests = mock(ResearchRequestRepository.class);
    private final CommandSender commands = mock(CommandSender.class);

    private ResearchSaga saga() {
        var profile = new MethodologyProfile(
                Fixtures.PROFILE_ID,
                "default",
                1,
                "em-1.0.0",
                MethodologyProfile.ScoreAggregator.WEIGHTED_GEOMETRIC,
                MethodologyProfile.defaultWeights(),
                MethodologyProfile.defaultParameters(),
                0.5,
                true,
                Fixtures.USER_ID,
                Fixtures.NOW);
        var profiles = mock(MethodologyProfileRepository.class);
        when(profiles.findById(any())).thenReturn(Optional.of(profile));
        when(profiles.requireDefault()).thenReturn(profile);
        var feedback = mock(TrendFeedbackRepository.class);
        when(feedback.findByDirection(any(), any())).thenReturn(List.of());
        return new ResearchSaga(
                requests,
                mock(TrendReportRepository.class),
                profiles,
                new ReportAssembler(),
                mock(DomainEventPublisher.class),
                commands,
                mock(ProgressBroadcaster.class),
                mock(ReportCache.class),
                mock(SavedDomainRepository.class),
                mock(UnrecognizedDirectionJournal.class),
                feedback,
                mock(dev.horizon.trends.application.port.ResearchMetrics.class),
                Clock.fixed(Fixtures.NOW, ZoneOffset.UTC));
    }

    private String engineOfSentCommand(AnalysisMode mode) {
        var request = ResearchRequest.submit(
                Fixtures.requester(),
                Fixtures.query(),
                AnalysisParameters.defaults(Fixtures.PROFILE_ID, mode),
                "idem-engine",
                Duration.ofMinutes(10),
                Fixtures.NOW);
        request.startCollecting(Fixtures.NOW.plusSeconds(1));
        when(requests.findByIdForUpdate(request.id())).thenReturn(Optional.of(request));

        saga().onCorpusCollected(request.id(), Fixtures.SNAPSHOT_ID, 100, List.of("arxiv"), List.of(), "msg-1");

        var captor = ArgumentCaptor.forClass(CommandSender.OutboundCommand.class);
        verify(commands).send(captor.capture());
        return ((ResearchSaga.AnalyzeDomainPayload) captor.getValue().payload()).engine();
    }

    @Test
    void aFastRequestIsCountedBySignals() {
        assertThat(engineOfSentCommand(AnalysisMode.FAST)).isEqualTo("signals");
    }

    @Test
    void aQualityRequestIsCountedBySignalsToo() {
        // Режим меняет бюджет сбора и срок, а не движок.
        assertThat(engineOfSentCommand(AnalysisMode.QUALITY)).isEqualTo("signals");
    }
}
