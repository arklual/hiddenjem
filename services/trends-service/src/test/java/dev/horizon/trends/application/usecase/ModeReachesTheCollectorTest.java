package dev.horizon.trends.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import dev.horizon.platform.common.event.DomainEventPublisher;
import dev.horizon.trends.application.port.CommandSender;
import dev.horizon.trends.application.port.DirectionCrosswalk;
import dev.horizon.trends.application.port.MethodologyProfileRepository;
import dev.horizon.trends.application.port.QuotaService;
import dev.horizon.trends.application.port.ResearchRequestRepository;
import dev.horizon.trends.config.ResearchProperties;
import dev.horizon.trends.domain.methodology.MethodologyProfile;
import dev.horizon.trends.domain.research.AnalysisMode;
import dev.horizon.trends.domain.research.AnalysisParameters;
import dev.horizon.trends.domain.research.ResearchRequest;
import dev.horizon.trends.support.Fixtures;

/**
 * Режим анализа доходит до сбора и до срока саги — проводка, а не вычисление.
 *
 * <p>Проверка существует потому, что её отсутствие ничего не сломает на виду. Качественный запрос,
 * потерявший режим по дороге, соберёт быстрый корпус и уложится в быстрый срок: отчёт выйдет
 * целым, и единственным следом останется то, что он не лучше быстрого. А режим, дошедший до сбора,
 * но не до срока, закроет таймаутом каждый качественный запрос посреди честно идущего сбора.
 *
 * <p>Свою половину — бюджеты по режиму — закрывает сбор.
 */
class ModeReachesTheCollectorTest {

    private static final Duration FAST_DEADLINE = Duration.ofMinutes(20);
    private static final Duration QUALITY_DEADLINE = Duration.ofMinutes(40);

    private final ResearchRequestRepository requests = mock(ResearchRequestRepository.class);
    private final MethodologyProfileRepository profiles = mock(MethodologyProfileRepository.class);
    private final CommandSender commands = mock(CommandSender.class);

    private SubmitResearchRequestUseCase useCase() {
        when(requests.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(profiles.requireDefault())
                .thenReturn(new MethodologyProfile(
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
                        Fixtures.NOW));
        return new SubmitResearchRequestUseCase(
                requests,
                profiles,
                mock(QuotaService.class),
                mock(DomainEventPublisher.class),
                commands,
                mock(DirectionCrosswalk.class),
                new ResearchProperties(FAST_DEADLINE, null, null, 0, 0, 0, 0, QUALITY_DEADLINE),
                Clock.fixed(Fixtures.NOW, ZoneOffset.UTC));
    }

    private ResearchRequest submit(AnalysisParameters parameters) {
        return useCase()
                .submit(Fixtures.requester(), "квантовые вычисления", parameters, null)
                .request();
    }

    private SubmitResearchRequestUseCase.CollectDomainCorpusPayload sentCollectCommand() {
        var captor = ArgumentCaptor.forClass(CommandSender.OutboundCommand.class);
        verify(commands).send(captor.capture());
        return (SubmitResearchRequestUseCase.CollectDomainCorpusPayload)
                captor.getValue().payload();
    }

    @Test
    void aQualityRequestAsksTheCollectorForQuality() {
        submit(AnalysisParameters.defaults(null, AnalysisMode.QUALITY));

        assertThat(sentCollectCommand().mode()).isEqualTo("quality");
    }

    @Test
    void aFastRequestNamesItsModeToo() {
        // Явное имя, а не пропущенное поле: сбор читает отсутствие как `fast`, но команда, в которой
        // режим написан, не зависит от того, какое умолчание окажется у принявшего её сбора.
        submit(AnalysisParameters.defaults(null, AnalysisMode.FAST));

        assertThat(sentCollectCommand().mode()).isEqualTo("fast");
    }

    @Test
    void theDeadlineFollowsTheMode() {
        var fast = submit(AnalysisParameters.defaults(null, AnalysisMode.FAST));
        var quality = submit(AnalysisParameters.defaults(null, AnalysisMode.QUALITY));

        assertThat(fast.deadlineAt()).isEqualTo(Fixtures.NOW.plus(FAST_DEADLINE));
        assertThat(quality.deadlineAt()).isEqualTo(Fixtures.NOW.plus(QUALITY_DEADLINE));
    }

    @Test
    void aProfileNamedByTheCallerIsReplacedByTheDefault() {
        // Параметры сохранённого направления хранят профиль, выбранный когда-то на снятом экране
        // методологии. Новый запуск считает умолчанием, а не им.
        var chosenLongAgo = AnalysisParameters.defaults(UUID.randomUUID(), AnalysisMode.FAST);

        var request = submit(chosenLongAgo);

        assertThat(request.parameters().methodologyProfileId()).isEqualTo(Fixtures.PROFILE_ID);
    }
}
