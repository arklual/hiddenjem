package dev.horizon.trends.application.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
import dev.horizon.trends.domain.feedback.TrendFeedback;
import dev.horizon.trends.domain.report.TrendReportId;
import dev.horizon.trends.support.Fixtures;

/**
 * Пометка «это не технология» доходит до движка — проводка, а не вычисление.
 *
 * <p>Замером установлено, что метод сам такие темы не отличает и отличить не сможет: «concrete
 * source passages», «retrieved passages», «stale documents» — грамматически правильные именные
 * группы, отличающиеся от имени технологии только смыслом. Балл эмерджентности даёт им 44.9–46.4
 * против 45.7–55.7 у настоящих, распределения перекрываются полностью; термхуд ставит обрывок
 * «state space» на 98-й перцентиль, выше всех настоящих тем. Числа — в
 * {@code docs/01-analysis/30-boundary-filter-findings.md}.
 *
 * <p>Человек отличает их с одного взгляда, и продукт этот взгляд записывал — но никуда не
 * передавал: ни один путь анализа обратную связь не читал. Аналитик вычёркивал тему, а следующий
 * прогон ставил её на то же место.
 *
 * <p>Тест проверяет именно передачу. Движок свою половину закрывает сам
 * ({@code tests/unit/test_suppressed_trends.py}), и без этой проверки обе половины были бы зелёными
 * при неработающей функции — ровно так уже случалось со ссылкой на отчёт у отслеживаемого
 * направления и с полем, которое молча отбрасывал слой HTTP.
 */
class SuppressedTrendsReachTheEngineTest {

    private final ResearchRequestRepository requests = mock(ResearchRequestRepository.class);
    private final CommandSender commands = mock(CommandSender.class);
    private final TrendFeedbackRepository feedback = mock(TrendFeedbackRepository.class);

    private ResearchSaga saga() {
        var profile = new dev.horizon.trends.domain.methodology.MethodologyProfile(
                Fixtures.PROFILE_ID,
                "default",
                1,
                "em-1.0.0",
                dev.horizon.trends.domain.methodology.MethodologyProfile.ScoreAggregator.WEIGHTED_GEOMETRIC,
                dev.horizon.trends.domain.methodology.MethodologyProfile.defaultWeights(),
                dev.horizon.trends.domain.methodology.MethodologyProfile.defaultParameters(),
                0.5,
                true,
                Fixtures.USER_ID,
                Fixtures.NOW);
        var profiles = mock(MethodologyProfileRepository.class);
        when(profiles.findById(any())).thenReturn(Optional.of(profile));
        when(profiles.requireDefault()).thenReturn(profile);
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

    private TrendFeedback mark(String trendKey, TrendFeedback.Verdict verdict) {
        return TrendFeedback.record(
                Fixtures.USER_ID, new TrendReportId(UUID.randomUUID()), trendKey, verdict, null, Fixtures.NOW);
    }

    private List<String> suppressedKeysOfSentCommand() {
        var request = Fixtures.pendingRequest();
        request.startCollecting(Fixtures.NOW.plusSeconds(1));
        when(requests.findByIdForUpdate(request.id())).thenReturn(Optional.of(request));

        saga().onCorpusCollected(request.id(), Fixtures.SNAPSHOT_ID, 100, List.of("arxiv"), List.of(), "msg-1");

        var captor = ArgumentCaptor.forClass(CommandSender.OutboundCommand.class);
        verify(commands).send(captor.capture());
        var payload = (ResearchSaga.AnalyzeDomainPayload) captor.getValue().payload();
        return payload.parameters().suppressedTrendKeys();
    }

    @Test
    void aTopicMarkedAsNoiseIsSentToTheEngine() {
        when(feedback.findByDirection(any(), any()))
                .thenReturn(List.of(mark("stale document", TrendFeedback.Verdict.NOISE)));

        assertThat(suppressedKeysOfSentCommand()).containsExactly("stale document");
    }

    @Test
    void onlyNoiseSuppresses() {
        // ALREADY_KNOWN — не повод скрывать: тема настоящая, и её отсутствие в отчёте было бы
        // враньём. Аналитик сказал «мы это знаем», а не «этого не существует».
        when(feedback.findByDirection(any(), any()))
                .thenReturn(List.of(
                        mark("stale document", TrendFeedback.Verdict.NOISE),
                        mark("speculative decod", TrendFeedback.Verdict.ALREADY_KNOWN),
                        mark("linear-time model", TrendFeedback.Verdict.RELEVANT)));

        assertThat(suppressedKeysOfSentCommand()).containsExactly("stale document");
    }

    @Test
    void theKeysAreSortedSoTheCommandIsByteStable() {
        // Порядок из базы не определён, а команда обязана быть воспроизводимой (ADR-0015): один и
        // тот же запрос не должен давать разные байты от прогона к прогону.
        when(feedback.findByDirection(any(), any()))
                .thenReturn(List.of(
                        mark("zeta", TrendFeedback.Verdict.NOISE),
                        mark("alpha", TrendFeedback.Verdict.NOISE),
                        mark("mu", TrendFeedback.Verdict.NOISE)));

        assertThat(suppressedKeysOfSentCommand()).containsExactly("alpha", "mu", "zeta");
    }

    @Test
    void noMarksMeansAnEmptyList() {
        // Не null: отсутствие пометок — обычное состояние, и оно не должно требовать отдельной
        // ветки ни у отправителя, ни у движка.
        when(feedback.findByDirection(any(), any())).thenReturn(List.of());

        assertThat(suppressedKeysOfSentCommand()).isEmpty();
    }
}
