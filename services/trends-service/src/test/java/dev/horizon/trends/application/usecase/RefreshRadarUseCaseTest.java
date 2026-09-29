package dev.horizon.trends.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import dev.horizon.platform.common.error.HorizonException;
import dev.horizon.platform.common.error.ProblemType;
import dev.horizon.trends.application.port.SavedDomainRepository;
import dev.horizon.trends.domain.research.AnalysisParameters;
import dev.horizon.trends.domain.research.RequesterRef;
import dev.horizon.trends.domain.saveddomain.SavedDomain;
import dev.horizon.trends.support.Fixtures;

/**
 * Batch refresh of the radar (UC-14).
 *
 * <p>What matters here is the refusals. A batch that stops at the first one, or that reports "did
 * not happen" for a direction that simply had nothing to recompute, sends the analyst to look at
 * the wrong directions — which is the only thing this screen exists to prevent.
 */
class RefreshRadarUseCaseTest {

    private static final RequesterRef REQUESTER = Fixtures.requester();

    /**
     * A submit that answers however the test needs.
     *
     * <p>Subclassed rather than faked behind a new interface: the real class has seven collaborators
     * this test never reaches, and inventing a seam in production code to avoid constructing them
     * would be shaping the design around the test rather than around the domain.
     */
    private static final class StubSubmit extends SubmitResearchRequestUseCase {
        private final Function<String, Result> answer;
        private final List<String> asked = new ArrayList<>();

        private StubSubmit(Function<String, Result> answer) {
            super(null, null, null, null, null, null, null, null);
            this.answer = answer;
        }

        private final List<Boolean> refreshFlags = new ArrayList<>();

        @Override
        public Result submit(
                RequesterRef requester,
                String rawQuery,
                AnalysisParameters parameters,
                String idempotencyKey,
                boolean refresh) {
            asked.add(rawQuery);
            refreshFlags.add(refresh);
            return answer.apply(rawQuery);
        }
    }

    private record StubSaved(List<SavedDomain> domains) implements SavedDomainRepository {
        @Override
        public List<SavedDomain> findByUser(UUID userId) {
            return domains;
        }

        @Override
        public Optional<SavedDomain> findByUserAndQuery(UUID userId, String normalizedQuery) {
            return Optional.empty();
        }

        @Override
        public long countByUser(UUID userId) {
            return domains.size();
        }

        @Override
        public SavedDomain save(SavedDomain domain) {
            return domain;
        }

        @Override
        public boolean delete(UUID userId, UUID id) {
            return false;
        }

        @Override
        public boolean existsForUser(UUID userId, UUID id) {
            return false;
        }
    }

    private static int savedCounter;

    /** Each call is saved one minute after the previous one, so ordering is testable. */
    private static SavedDomain saved(String query) {
        return new SavedDomain(
                UUID.randomUUID(),
                Fixtures.USER_ID,
                dev.horizon.trends.domain.research.TechnologyDomainQuery.of(query),
                Fixtures.parameters(),
                null,
                Instant.parse("2026-03-01T10:00:00Z").plusSeconds(60L * savedCounter++));
    }

    private static SubmitResearchRequestUseCase.Result accepted() {
        return new SubmitResearchRequestUseCase.Result(
                Fixtures.pendingRequest(), SubmitResearchRequestUseCase.Result.Outcome.ACCEPTED);
    }

    private static SubmitResearchRequestUseCase.Result alreadyRunning() {
        return new SubmitResearchRequestUseCase.Result(
                Fixtures.pendingRequest(), SubmitResearchRequestUseCase.Result.Outcome.ALREADY_RUNNING);
    }

    private static List<RefreshRadarUseCase.RefreshResult> refresh(
            List<SavedDomain> domains, Function<String, SubmitResearchRequestUseCase.Result> answer) {
        return new RefreshRadarUseCase(new StubSaved(domains), new StubSubmit(answer)).refresh(REQUESTER);
    }

    @Test
    void queuesEveryTrackedDirection() {
        var results =
                refresh(List.of(saved("искусственный интеллект"), saved("квантовые вычисления")), query -> accepted());

        assertThat(results)
                .extracting(RefreshRadarUseCase.RefreshResult::outcome)
                .containsExactly(RefreshRadarUseCase.Outcome.ACCEPTED, RefreshRadarUseCase.Outcome.ACCEPTED);
    }

    @Test
    void keepsTheOrderTheDirectionsWereSavedIn() {
        // When the quota runs out mid-batch, the analyst has to be able to tell which directions did
        // not make it. That is only readable if the order is the one they already know.
        var results = refresh(List.of(saved("первое"), saved("второе"), saved("третье")), query -> accepted());

        assertThat(results)
                .extracting(RefreshRadarUseCase.RefreshResult::query)
                .containsExactly("первое", "второе", "третье");
    }

    @Test
    void aDirectionThatIsAlreadyBeingAnalysedIsNotCalledFreshlyQueued() {
        // Three answers, three different actions for the analyst: wait for a run that just started,
        // wait for one already going, or open the report that exists. Folding this one into
        // "поставлено на пересчёт" would promise a queue position that was never taken.
        var results = refresh(List.of(saved("искусственный интеллект")), query -> alreadyRunning());

        assertThat(results.getFirst().outcome()).isEqualTo(RefreshRadarUseCase.Outcome.ALREADY_RUNNING);
        assertThat(results.getFirst().reason()).isNull();
        assertThat(results.getFirst().requestId())
                .as("за идущим запросом можно следить")
                .isNotNull();
    }

    @Test
    void everyDirectionIsRecomputedRatherThanServedFromCache() {
        // P7. Кнопка называется «Обновить все» и заводилась ради обновления. До сих пор она
        // отвечала «уже свежее» на все направления сразу — то есть не делала ничего.
        var submit = new StubSubmit(query -> accepted());
        new RefreshRadarUseCase(new StubSaved(List.of(saved("искусственный интеллект"), saved("финтех"))), submit)
                .refresh(REQUESTER);

        assertThat(submit.refreshFlags).containsExactly(true, true);
    }

    @Test
    void runningOutOfQuotaDoesNotStopTheRemainingDirections() {
        // Stopping would make the answer depend on where the budget happened to run out, and would
        // leave the analyst without a row for directions nobody even tried.
        var results = refresh(List.of(saved("первое"), saved("второе"), saved("третье")), query -> {
            throw HorizonException.quotaExceeded("Квота исчерпана");
        });

        assertThat(results).hasSize(3);
        assertThat(results).allSatisfy(result -> assertThat(result.outcome())
                .isEqualTo(RefreshRadarUseCase.Outcome.QUOTA_EXCEEDED));
        assertThat(results.getFirst().reason()).isNotBlank();
    }

    @Test
    void oneRefusalDoesNotUndoTheDirectionsAlreadyQueued() {
        // N independent requests, not a transaction: rolling back what was accepted would be harm
        // with no purpose.
        var results = refresh(List.of(saved("хорошее"), saved("плохое")), query -> {
            if (query.equals("плохое")) {
                throw new HorizonException(ProblemType.VALIDATION_ERROR, "слишком короткий запрос");
            }
            return accepted();
        });

        assertThat(results.get(0).outcome()).isEqualTo(RefreshRadarUseCase.Outcome.ACCEPTED);
        assertThat(results.get(1).outcome()).isEqualTo(RefreshRadarUseCase.Outcome.FAILED);
        assertThat(results.get(1).reason()).contains("короткий");
    }

    @Test
    void distinguishesAQuotaRefusalFromAnyOtherOne() {
        // The analyst waits an hour in one case and fixes the direction in the other.
        var quota = refresh(List.of(saved("искусственный интеллект")), query -> {
            throw HorizonException.quotaExceeded("Квота исчерпана");
        });
        var other = refresh(List.of(saved("искусственный интеллект")), query -> {
            throw new HorizonException(ProblemType.VALIDATION_ERROR, "нет");
        });

        assertThat(quota.getFirst().outcome()).isEqualTo(RefreshRadarUseCase.Outcome.QUOTA_EXCEEDED);
        assertThat(other.getFirst().outcome()).isEqualTo(RefreshRadarUseCase.Outcome.FAILED);
    }

    @Test
    void anEmptyPortfolioIsAnAnswerRatherThanAnError() {
        assertThat(refresh(List.of(), query -> accepted())).isEmpty();
    }

    @Test
    void goesThroughTheOrdinarySubmitSoQuotaAndCachingStillApply() {
        // The one rule this increment must not break: a batch is not a way round the limit that
        // exists because external sources cost money.
        var submit = new StubSubmit(query -> accepted());
        new RefreshRadarUseCase(
                        new StubSaved(List.of(saved("искусственный интеллект"), saved("квантовые вычисления"))), submit)
                .refresh(REQUESTER);

        assertThat(submit.asked).containsExactly("искусственный интеллект", "квантовые вычисления");
    }

    @Test
    void carriesTheRequestIdForEveryDirectionThatWasQueued() {
        // Without it the client cannot follow the analysis it has just started.
        var results = refresh(List.of(saved("искусственный интеллект")), query -> accepted());

        assertThat(results.getFirst().requestId()).isNotNull();
    }

    @Test
    void carriesNoRequestIdForADirectionThatWasRefused() {
        var results = refresh(List.of(saved("искусственный интеллект")), query -> {
            throw HorizonException.quotaExceeded("Квота исчерпана");
        });

        assertThat(results.getFirst().requestId()).isNull();
    }

    @Test
    void theResultIsImmutableToItsCaller() {
        assertThat(refresh(List.of(saved("искусственный интеллект")), query -> accepted()))
                .isUnmodifiable();
    }
}
