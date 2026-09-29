package dev.horizon.trends.domain.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import dev.horizon.platform.common.error.HorizonException;
import dev.horizon.trends.domain.report.TrendReportId;
import dev.horizon.trends.support.Fixtures;

/** Invariants I1–I6 of {@link ResearchRequest} (domain-model §4.1). */
class ResearchRequestInvariantsTest {

    @Nested
    @DisplayName("I1 — только легальные переходы состояний")
    class LegalTransitionsOnly {

        @Test
        void illegalTransitionIsRejected() {
            var request = Fixtures.pendingRequest();
            // PENDING → ASSEMBLING skips two steps and must not be reachable.
            assertThatThrownBy(() -> request.startAssembling(UUID.randomUUID(), Fixtures.NOW))
                    .isInstanceOf(HorizonException.class)
                    .hasMessageContaining("Недопустимый переход");
            assertThat(request.status()).isEqualTo(ResearchStatus.PENDING);
        }

        @Test
        void repeatingTheSameTransitionIsANoOp() {
            var request = Fixtures.pendingRequest();
            request.startCollecting(Fixtures.NOW.plusSeconds(1));
            var percentAfterFirst = request.progress().percent();

            assertThatCode(() -> request.startCollecting(Fixtures.NOW.plusSeconds(2)))
                    .doesNotThrowAnyException();
            assertThat(request.status()).isEqualTo(ResearchStatus.COLLECTING);
            assertThat(request.progress().percent()).isEqualTo(percentAfterFirst);
        }
    }

    @Nested
    @DisplayName("I2 — параметры в допустимых диапазонах")
    class ParametersWithinRange {

        @Test
        void topNBelowTheMinimumIsRejected() {
            assertThatThrownBy(() -> new AnalysisParameters(
                            AnalysisParameters.MIN_TOP_N - 1,
                            7,
                            java.util.Set.of(),
                            0.0,
                            false,
                            Fixtures.PROFILE_ID,
                            AnalysisMode.FAST))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void yearsWindowAboveTheMaximumIsRejected() {
            assertThatThrownBy(() -> new AnalysisParameters(
                            15,
                            AnalysisParameters.MAX_YEARS_WINDOW + 1,
                            java.util.Set.of(),
                            0.0,
                            false,
                            Fixtures.PROFILE_ID,
                            AnalysisMode.FAST))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("I3/I4 — терминальное состояние всегда обосновано")
    class TerminalStatesAreJustified {

        @Test
        void completedAlwaysCarriesAReport() {
            var request = Fixtures.assemblingRequest();
            request.complete(TrendReportId.generate(), 3, Fixtures.NOW.plusSeconds(90));

            assertThat(request.status()).isEqualTo(ResearchStatus.COMPLETED);
            assertThat(request.reportId()).isPresent();
        }

        @Test
        void rehydratingCompletedWithoutAReportIsRefused() {
            assertThatThrownBy(() -> rehydrate(ResearchStatus.COMPLETED, null, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("I3");
        }

        @Test
        void rehydratingFailedWithoutACauseIsRefused() {
            assertThatThrownBy(() -> rehydrate(ResearchStatus.FAILED, null, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("I4");
        }

        @Test
        void failureRecordsTheCauseAndTheFact() {
            var request = Fixtures.pendingRequest();
            request.drainEvents();
            request.fail(FailureInfo.timeout(), Fixtures.NOW.plusSeconds(600));

            assertThat(request.status()).isEqualTo(ResearchStatus.FAILED);
            assertThat(request.failure()).map(FailureInfo::code).contains(FailureInfo.SAGA_TIMEOUT);
            assertThat(request.drainEvents()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("I5 — прогресс не убывает")
    class ProgressNeverDecreases {

        @Test
        void aLaterEventWithALowerPercentDoesNotMoveTheBarBack() {
            var request = Fixtures.pendingRequest();
            request.startCollecting(Fixtures.NOW.plusSeconds(1));
            request.corpusCollected(Fixtures.SNAPSHOT_ID, Fixtures.corpusCoverage(), Fixtures.NOW.plusSeconds(2));
            request.reportAnalysisProgress(80, "Почти готово", Fixtures.NOW.plusSeconds(30));
            int high = request.progress().percent();

            request.reportAnalysisProgress(5, "Запоздавшее событие", Fixtures.NOW.plusSeconds(31));

            assertThat(request.progress().percent()).isEqualTo(high);
        }

        @Test
        void collectionProgressMovesTheBarInsideTheCollectingStage() {
            // Без хода сбора полоса минутами стояла на 5 % и экран выглядел зависшим.
            var request = Fixtures.pendingRequest();
            request.startCollecting(Fixtures.NOW.plusSeconds(1));

            request.reportCollectionProgress(50, "Ответили 6 из 12 источников", Fixtures.NOW.plusSeconds(40));

            assertThat(request.progress().stage()).isEqualTo(AnalysisStage.COLLECTING);
            assertThat(request.progress().percent())
                    .isEqualTo(AnalysisStage.COLLECTING.globalPercent(50))
                    .isGreaterThan(AnalysisStage.COLLECTING.startPercent());
            assertThat(request.progress().message()).isEqualTo("Ответили 6 из 12 источников");
        }

        @Test
        void anOlderCollectionTickDoesNotRewindTheMessage() {
            var request = Fixtures.pendingRequest();
            request.startCollecting(Fixtures.NOW.plusSeconds(1));
            request.reportCollectionProgress(50, "Ответили 18 из 21 источника", Fixtures.NOW.plusSeconds(40));

            request.reportCollectionProgress(40, "Ответили 15 из 21 источника", Fixtures.NOW.plusSeconds(41));

            assertThat(request.progress().message()).isEqualTo("Ответили 18 из 21 источника");
        }

        @Test
        void aLateCollectionTickDoesNotRewriteTheAnalysisStage() {
            var request = Fixtures.pendingRequest();
            request.startCollecting(Fixtures.NOW.plusSeconds(1));
            request.corpusCollected(Fixtures.SNAPSHOT_ID, Fixtures.corpusCoverage(), Fixtures.NOW.plusSeconds(2));
            var analyzing = request.progress();

            request.reportCollectionProgress(90, "Запоздавший ход сбора", Fixtures.NOW.plusSeconds(3));

            assertThat(request.progress()).isEqualTo(analyzing);
        }

        @Test
        void progressForANonAnalyzingRequestIsIgnored() {
            var request = Fixtures.pendingRequest();
            request.reportAnalysisProgress(50, "Не относится к этой стадии", Fixtures.NOW.plusSeconds(5));

            assertThat(request.progress().stage()).isEqualTo(AnalysisStage.QUEUED);
            assertThat(request.progress().percent()).isZero();
        }
    }

    @Nested
    @DisplayName("I6 — терминальное состояние неизменяемо")
    class TerminalIsImmutable {

        @Test
        void failingAnAlreadyFinishedRequestIsANoOp() {
            var request = Fixtures.assemblingRequest();
            var reportId = TrendReportId.generate();
            request.complete(reportId, 3, Fixtures.NOW.plusSeconds(90));
            request.drainEvents();

            request.fail(FailureInfo.timeout(), Fixtures.NOW.plusSeconds(120));

            assertThat(request.status()).isEqualTo(ResearchStatus.COMPLETED);
            assertThat(request.reportId()).contains(reportId);
            assertThat(request.drainEvents()).isEmpty();
        }

        @Test
        void cancellingAFinishedRequestIsAConflict() {
            var request = Fixtures.assemblingRequest();
            request.complete(TrendReportId.generate(), 3, Fixtures.NOW.plusSeconds(90));

            assertThatThrownBy(() -> request.cancel(Fixtures.NOW.plusSeconds(100)))
                    .isInstanceOf(HorizonException.class)
                    .hasMessageContaining("уже завершён");
        }
    }

    @Nested
    class DomainBehaviour {

        @Test
        void anEmptyCorpusFailsTheRequestInsteadOfStartingAnalysis() {
            var request = Fixtures.pendingRequest();
            request.startCollecting(Fixtures.NOW.plusSeconds(1));

            request.corpusCollected(
                    Fixtures.SNAPSHOT_ID, new CorpusCoverage(0, List.of(), List.of()), Fixtures.NOW.plusSeconds(2));

            assertThat(request.status()).isEqualTo(ResearchStatus.FAILED);
            assertThat(request.failure()).map(FailureInfo::code).contains(FailureInfo.NO_DOCUMENTS_FOUND);
        }

        @Test
        void unavailableSourcesMarkTheRequestPartial() {
            var request = Fixtures.pendingRequest();
            request.startCollecting(Fixtures.NOW.plusSeconds(1));

            request.corpusCollected(
                    Fixtures.SNAPSHOT_ID,
                    new CorpusCoverage(50, List.of("arxiv"), List.of("patents")),
                    Fixtures.NOW.plusSeconds(2));

            assertThat(request.partial()).isTrue();
            assertThat(request.status()).isEqualTo(ResearchStatus.ANALYZING);
        }

        @Test
        void aRequestIsOverdueOnlyWhileItIsStillRunning() {
            var request = Fixtures.pendingRequest();
            var afterDeadline = Fixtures.NOW.plus(Duration.ofMinutes(11));

            assertThat(request.isOverdue(afterDeadline)).isTrue();
            request.cancel(afterDeadline);
            assertThat(request.isOverdue(afterDeadline)).isFalse();
        }

        @Test
        void ownersSeeTheirOwnRequestsAndAdministratorsSeeEverything() {
            var request = Fixtures.pendingRequest();

            assertThat(request.isVisibleTo(ReportViewer.of(Fixtures.USER_ID))).isTrue();
            assertThat(request.isVisibleTo(ReportViewer.of(UUID.randomUUID()))).isFalse();
            assertThat(request.isVisibleTo(new ReportViewer(UUID.randomUUID(), null, true)))
                    .isTrue();
        }

        @Test
        void onlyATimedOutRequestReopensForALateResult() {
            // Развилка узкая нарочно. Таймаут саги — это утверждение «мы перестали ждать», а не
            // «работа не сделана»: движок не останавливается, и ответ приходит. Все прочие
            // терминальные состояния означают ровно то, что означают, и позднее сообщение их не
            // пересматривает.
            var timedOut = Fixtures.pendingRequest();
            timedOut.startCollecting(Fixtures.NOW.plusSeconds(1));
            timedOut.corpusCollected(Fixtures.SNAPSHOT_ID, Fixtures.corpusCoverage(), Fixtures.NOW.plusSeconds(2));
            timedOut.fail(FailureInfo.timeout(), Fixtures.NOW.plusSeconds(600));

            assertThat(timedOut.reopenAfterTimeout(Fixtures.NOW.plusSeconds(700)))
                    .isTrue();
            assertThat(timedOut.status()).isEqualTo(ResearchStatus.ANALYZING);
            assertThat(timedOut.failure()).isEmpty();
            assertThat(timedOut.finishedAt()).isEmpty();

            var broken = Fixtures.pendingRequest();
            broken.fail(new FailureInfo(FailureInfo.COLLECTION_FAILED, "Источник недоступен", true), Fixtures.NOW);
            assertThat(broken.reopenAfterTimeout(Fixtures.NOW.plusSeconds(1))).isFalse();
            assertThat(broken.status()).isEqualTo(ResearchStatus.FAILED);

            var cancelled = Fixtures.pendingRequest();
            cancelled.cancel(Fixtures.NOW);
            assertThat(cancelled.reopenAfterTimeout(Fixtures.NOW.plusSeconds(1)))
                    .isFalse();

            var running = Fixtures.pendingRequest();
            assertThat(running.reopenAfterTimeout(Fixtures.NOW.plusSeconds(1))).isFalse();
            assertThat(running.status()).isEqualTo(ResearchStatus.PENDING);
        }

        @Test
        void aReopenedRequestKeepsItsProgressInsteadOfRewindingToZero() {
            // Полоса уже дошла до середины анализа, и откат её назад читался бы как «начали заново»
            // — притом что работа как раз закончена.
            var request = Fixtures.pendingRequest();
            request.startCollecting(Fixtures.NOW.plusSeconds(1));
            request.corpusCollected(Fixtures.SNAPSHOT_ID, Fixtures.corpusCoverage(), Fixtures.NOW.plusSeconds(2));
            request.reportAnalysisProgress(60, "Оценка", Fixtures.NOW.plusSeconds(3));
            int reached = request.progress().percent();
            request.fail(FailureInfo.timeout(), Fixtures.NOW.plusSeconds(600));

            request.reopenAfterTimeout(Fixtures.NOW.plusSeconds(700));

            assertThat(request.progress().percent()).isEqualTo(reached);
            assertThat(request.progress().stage()).isEqualTo(AnalysisStage.ANALYZING);
        }

        @Test
        void etaIsUnavailableBeforeAnyProgressAndAfterCompletion() {
            var request = Fixtures.pendingRequest();
            assertThat(request.etaSeconds(Fixtures.NOW.plusSeconds(10))).isEmpty();

            // Прогресс стоит на нижней границе стадии сбора: сколько она продлится, он не
            // сообщает. Экстраполяция здесь давала бы +19 секунд оценки на каждую секунду
            // ожидания — те самые «15 минут» на запросе, идущем меньше минуты.
            request.startCollecting(Fixtures.NOW.plusSeconds(1));
            assertThat(request.etaSeconds(Fixtures.NOW.plusSeconds(30))).isEmpty();

            request.cancel(Fixtures.NOW.plusSeconds(40));
            assertThat(request.etaSeconds(Fixtures.NOW.plusSeconds(50))).isEmpty();
        }

        @Test
        void etaAppearsOnceProgressMovesInsideTheStage() {
            var request = Fixtures.pendingRequest();
            request.startCollecting(Fixtures.NOW.plusSeconds(1));
            request.corpusCollected(Fixtures.SNAPSHOT_ID, Fixtures.corpusCoverage(), Fixtures.NOW.plusSeconds(2));

            // Всё ещё нижняя граница полосы ANALYZING (40%).
            assertThat(request.etaSeconds(Fixtures.NOW.plusSeconds(30))).isEmpty();

            // 40 + 45 * 0.2 = 49%: за 30 секунд пройдена половина работы, значит впереди примерно
            // столько же. Проверяем величину, а не факт наличия значения.
            request.reportAnalysisProgress(20, "Кластеризация", Fixtures.NOW.plusSeconds(3));
            assertThat(request.etaSeconds(Fixtures.NOW.plusSeconds(31))).hasValue(31L);
        }

        @Test
        void etaNeverPromisesMoreTimeThanTheDeadlineAllows() {
            var request = Fixtures.pendingRequest(); // дедлайн саги — NOW + 10 минут
            request.startCollecting(Fixtures.NOW.plusSeconds(1));
            request.corpusCollected(Fixtures.SNAPSHOT_ID, Fixtures.corpusCoverage(), Fixtures.NOW.plusSeconds(2));
            request.reportAnalysisProgress(2, "Извлечение терминов", Fixtures.NOW.plusSeconds(3));

            // Наивная экстраполяция дала бы ~432 секунды, но жить запросу осталось 299:
            // обещать остаток больше дедлайна нельзя, его всё равно оборвут.
            var now = Fixtures.NOW.plusSeconds(301);
            assertThat(request.etaSeconds(now)).hasValue(299L);
        }
    }

    private static ResearchRequest rehydrate(ResearchStatus status, TrendReportId reportId, FailureInfo failure) {
        return new ResearchRequest(
                ResearchRequestId.generate(),
                Fixtures.requester(),
                Fixtures.query(),
                Fixtures.parameters(),
                null,
                Fixtures.NOW,
                Fixtures.NOW.plus(Duration.ofMinutes(10)),
                status,
                AnalysisProgress.queued(Fixtures.NOW),
                1,
                null,
                CorpusCoverage.empty(),
                null,
                reportId,
                false,
                failure,
                null,
                null,
                0L);
    }
}
