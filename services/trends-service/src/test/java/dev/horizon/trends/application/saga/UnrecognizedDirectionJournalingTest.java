package dev.horizon.trends.application.saga;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import dev.horizon.platform.common.event.DomainEventPublisher;
import dev.horizon.trends.application.dto.AnalysisResult;
import dev.horizon.trends.application.port.CommandSender;
import dev.horizon.trends.application.port.MethodologyProfileRepository;
import dev.horizon.trends.application.port.ProgressBroadcaster;
import dev.horizon.trends.application.port.ReportCache;
import dev.horizon.trends.application.port.ResearchRequestRepository;
import dev.horizon.trends.application.port.SavedDomainRepository;
import dev.horizon.trends.application.port.TrendReportRepository;
import dev.horizon.trends.application.port.UnrecognizedDirectionJournal;
import dev.horizon.trends.application.usecase.ReportAssembler;
import dev.horizon.trends.domain.direction.UnrecognizedDirection;
import dev.horizon.trends.domain.report.TrendReport;
import dev.horizon.trends.domain.research.ResearchRequest;
import dev.horizon.trends.support.Fixtures;

/**
 * Направление, которого словарь не знает, попадает в очередь пополнения.
 *
 * <p>До журнала перекрёстный словарь пополнялся догадкой: статьи добавлялись по прочтению корпуса, а
 * не по тому, что набирают аналитики. Такой словарь покрывает ровно то, о чём подумал автор, и
 * молчит о том, что спрашивают на самом деле.
 *
 * <p>Проверяется здесь не запись как таковая, а три решения вокруг неё: журнал ведётся только когда
 * есть о чём, отказ журнала не стоит отчёта, и запись попадает в область той организации, чей
 * аналитик спрашивал.
 */
class UnrecognizedDirectionJournalingTest {

    private final ResearchRequestRepository requests = mock(ResearchRequestRepository.class);
    private final TrendReportRepository reports = mock(TrendReportRepository.class);
    private final UnrecognizedDirectionJournal journal = mock(UnrecognizedDirectionJournal.class);

    private ResearchSaga saga() {
        return new ResearchSaga(
                requests,
                reports,
                mock(MethodologyProfileRepository.class),
                new ReportAssembler(),
                mock(DomainEventPublisher.class),
                mock(CommandSender.class),
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
        when(reports.nextVersionFor(anyString(), anyString())).thenReturn(1);
        when(reports.findLatestForDirection(anyString(), anyString(), any())).thenReturn(Optional.empty());
        when(reports.save(any())).thenAnswer(call -> call.getArgument(0));
        return request;
    }

    /** Выдача движка с заданным исходом распознавания направления. */
    private static AnalysisResult result(ResearchRequest request, Boolean recognized, List<String> suggestions) {
        return new AnalysisResult(
                request.id().value().toString(),
                1,
                Fixtures.SNAPSHOT_ID.toString(),
                "em-1.0.0",
                Fixtures.PROFILE_ID.toString(),
                "WEIGHTED_GEOMETRIC",
                "tfidf-svd-384-v1",
                120,
                340,
                false,
                0,
                recognized,
                suggestions,
                LocalDate.of(2019, 3, 1),
                LocalDate.of(2026, 3, 1),
                Map.of("scoring", 120.0),
                14,
                List.of(Fixtures.analyzedTrend(1, "квантовые сенсоры")));
    }

    @Test
    @DisplayName("нераспознанное направление попадает в очередь вместе с подсказками")
    void anUnrecognisedDirectionEntersTheQueue() {
        var request = arrivedAtAnalysis();

        saga().onDomainAnalyzed(result(request, Boolean.FALSE, List.of("квантовые вычисления")));

        var recorded = ArgumentCaptor.forClass(UnrecognizedDirection.class);
        verify(journal).record(recorded.capture());
        assertThat(recorded.getValue().normalizedQuery())
                .isEqualTo(request.query().normalized());
        assertThat(recorded.getValue().suggestions()).containsExactly("квантовые вычисления");
        assertThat(recorded.getValue().occurrences()).isEqualTo(1);
    }

    @Test
    @DisplayName("запись попадает в область организации спросившего")
    void therecordIsScopedToTheAskersOrganisation() {
        // Перечень направлений, которые исследует банк, — его повестка. Запись, попавшая в чужую
        // область, была бы разглашением, а не удобством, и заметили бы это не скоро.
        var request = arrivedAtAnalysis();

        saga().onDomainAnalyzed(result(request, Boolean.FALSE, List.of()));

        var recorded = ArgumentCaptor.forClass(UnrecognizedDirection.class);
        verify(journal).record(recorded.capture());
        assertThat(recorded.getValue().organizationId())
                .isEqualTo(request.requester().organizationId());
    }

    @Test
    @DisplayName("распознанное направление в очередь не попадает")
    void arecognisedDirectionDoesNotEnterTheQueue() {
        // Очередь, куда падает каждый запрос, — это протокол, а не очередь: куратор не сможет
        // отличить пробел в словаре от обычной работы.
        var request = arrivedAtAnalysis();

        saga().onDomainAnalyzed(result(request, Boolean.TRUE, List.of()));

        verify(journal, never()).record(any());
    }

    @Test
    @DisplayName("выдача без поля распознавания в очередь не попадает")
    void anEventWithoutTheFieldDoesNotEnterTheQueue() {
        // Событие, выпущенное движком до появления поля, ничего не говорит о распознавании.
        // Считать его нераспознанным значило бы заполнить очередь задачами, которых нет.
        var request = arrivedAtAnalysis();

        saga().onDomainAnalyzed(result(request, null, null));

        verify(journal, never()).record(any());
    }

    @Test
    @DisplayName("отказ журнала не стоит отчёта")
    void afailingJournalDoesNotCostTheReport() {
        // Журнал существует для того, кто пополняет словарь; отчёт — для аналитика, который ждёт.
        // Разменять второе на первое значило бы делать продукт тем ненадёжнее, чем больше в нём
        // измерений, — направление неверное для обоих.
        var request = arrivedAtAnalysis();
        willThrow(new IllegalStateException("журнал недоступен")).given(journal).record(any());

        saga().onDomainAnalyzed(result(request, Boolean.FALSE, List.of()));

        var saved = ArgumentCaptor.forClass(TrendReport.class);
        verify(reports).save(saved.capture());
        assertThat(saved.getValue().trends()).isNotEmpty();
        assertThat(request.status()).isEqualTo(dev.horizon.trends.domain.research.ResearchStatus.COMPLETED);
    }
}
