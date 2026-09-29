package dev.horizon.trends.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.horizon.platform.common.event.DomainEvent;
import dev.horizon.platform.common.event.DomainEventPublisher;
import dev.horizon.trends.application.port.MethodologyProfileRepository;
import dev.horizon.trends.application.port.PageResult;
import dev.horizon.trends.application.port.QuotaService;
import dev.horizon.trends.application.port.QuotaService.QuotaBudget;
import dev.horizon.trends.application.port.ResearchRequestRepository;
import dev.horizon.trends.config.ResearchProperties;
import dev.horizon.trends.domain.methodology.MethodologyProfile;
import dev.horizon.trends.domain.research.AnalysisParameters;
import dev.horizon.trends.domain.research.ReportViewer;
import dev.horizon.trends.domain.research.ResearchRequest;
import dev.horizon.trends.domain.research.ResearchRequestId;
import dev.horizon.trends.domain.research.ResearchStatus;
import dev.horizon.trends.domain.research.TechnologyDomainQuery;
import dev.horizon.trends.support.Fixtures;

/**
 * A direction that is already being analysed must not be analysed twice (BR-A24…BR-A26).
 *
 * <p>The analysis takes up to a minute and a half, and nothing on screen says it has started, so the
 * analyst presses again. The freshness check of BR-A8 does not help: it looks for a <b>completed</b>
 * result, and the first run has not finished. Without this guard the second press spends quota and
 * external-source budget on a report that already exists in the making.
 */
class DuplicateRunTest {

    private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");
    private static final UUID OTHER_USER = UUID.fromString("99999999-9999-4999-8999-999999999999");

    private static final class CountingQuotas implements QuotaService {
        private int consumed;

        @Override
        public void checkAndConsume(UUID userId, UUID organizationId) {
            consumed++;
        }

        @Override
        public void refund(UUID userId, UUID organizationId, Instant chargedAt) {}

        @Override
        public Optional<QuotaBudget> remaining(UUID userId, UUID organizationId) {
            return Optional.empty();
        }
    }

    /**
     * Answers the two lookups from what it was given and records how it was asked.
     *
     * <p>The recorded arguments are the point of P1 and BR-A47: the use case may only claim "already
     * running" for the same question of the same organisation, and the only place that promise is
     * visible is the call it makes.
     */
    private static final class Requests implements ResearchRequestRepository {
        private final ResearchRequest active;
        private final ResearchRequest fresh;
        private final ResearchRequest byKey;
        private int saved;
        private String askedQuery;
        private AnalysisParameters askedParameters;
        private boolean freshnessConsulted;
        private UUID freshnessOrganization;
        private UUID askedOrganization;

        private Requests(ResearchRequest active, ResearchRequest fresh, ResearchRequest byKey) {
            this.active = active;
            this.fresh = fresh;
            this.byKey = byKey;
        }

        static Requests withActive(ResearchRequest active) {
            return new Requests(active, null, null);
        }

        static Requests empty() {
            return new Requests(null, null, null);
        }

        @Override
        public Optional<ResearchRequest> findActive(
                UUID organizationId, String normalizedQuery, AnalysisParameters parameters) {
            askedOrganization = organizationId;
            askedQuery = normalizedQuery;
            askedParameters = parameters;
            // The stub stands in for the query's WHERE clause: another organisation's request is not
            // a match, and neither is the same direction asked with different parameters — exactly
            // the predicates the JPQL spells out.
            if (active == null
                    || !active.requester().organizationId().equals(organizationId)
                    || !active.query().normalized().equals(normalizedQuery)
                    || !active.parameters().cacheDiscriminator().equals(parameters.cacheDiscriminator())) {
                return Optional.empty();
            }
            return Optional.of(active);
        }

        @Override
        public Optional<ResearchRequest> findFreshCompleted(
                String normalizedQuery, AnalysisParameters parameters, UUID organizationId, Instant since) {
            freshnessConsulted = true;
            freshnessOrganization = organizationId;
            return Optional.ofNullable(fresh);
        }

        @Override
        public Optional<ResearchRequest> findByIdempotencyKey(UUID userId, String idempotencyKey) {
            return Optional.ofNullable(byKey);
        }

        @Override
        public ResearchRequest save(ResearchRequest request) {
            saved++;
            return request;
        }

        @Override
        public Optional<ResearchRequest> findById(ResearchRequestId id) {
            return Optional.empty();
        }

        @Override
        public Optional<ResearchRequest> findByIdForUpdate(ResearchRequestId id) {
            return Optional.empty();
        }

        @Override
        public PageResult<ResearchRequest> findHistory(
                ReportViewer viewer, boolean onlyMine, ResearchStatus status, int page, int size) {
            return new PageResult<>(List.of(), 0, page, size);
        }

        @Override
        public List<ResearchRequest> findOverdue(Instant now, int limit) {
            return List.of();
        }

        @Override
        public long countSubmittedSince(UUID userId, Instant since) {
            return 0;
        }

        @Override
        public long countSubmittedByOrganizationSince(UUID organizationId, Instant since) {
            return 0;
        }
    }

    private static final class Profiles implements MethodologyProfileRepository {
        private final MethodologyProfile profile = new MethodologyProfile(
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
                NOW);

        @Override
        public Optional<MethodologyProfile> findById(UUID id) {
            return Optional.of(profile);
        }

        @Override
        public MethodologyProfile requireDefault() {
            return profile;
        }
    }

    private static SubmitResearchRequestUseCase useCase(Requests requests, CountingQuotas quotas) {
        return new SubmitResearchRequestUseCase(
                requests,
                new Profiles(),
                quotas,
                new DomainEventPublisher() {
                    @Override
                    public void publish(Collection<? extends DomainEvent> events) {}
                },
                command -> {},
                // Движок в этих сценариях не участвует: проверяются повторы и квота, а не словарь.
                new dev.horizon.trends.application.port.DirectionCrosswalk() {
                    @Override
                    public java.util.List<String> targetsFor(String query) {
                        return java.util.List.of();
                    }

                    @Override
                    public Resolution resolve(String query) {
                        return Resolution.unknown();
                    }
                },
                new ResearchProperties(null, null, null, 0, 0, 0, 0, null),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static ResearchRequest runningRequest(UUID userId) {
        return runningRequest(userId, Fixtures.ORGANIZATION_ID);
    }

    private static ResearchRequest runningRequest(UUID userId, UUID organizationId) {
        return ResearchRequest.submit(
                new dev.horizon.trends.domain.research.RequesterRef(userId, organizationId),
                TechnologyDomainQuery.of("квантовые вычисления"),
                Fixtures.parameters(),
                "первый-ключ",
                Duration.ofMinutes(10),
                NOW.minusSeconds(30));
    }

    private static SubmitResearchRequestUseCase.Result submit(SubmitResearchRequestUseCase useCase) {
        return useCase.submit(Fixtures.requester(), "Квантовые Вычисления", Fixtures.parameters(), null);
    }

    @Test
    void aSecondPressReturnsTheRunningAnalysisInsteadOfStartingAnother() {
        var running = runningRequest(Fixtures.USER_ID);
        var requests = Requests.withActive(running);

        var result = submit(useCase(requests, new CountingQuotas()));

        assertThat(result.outcome()).isEqualTo(SubmitResearchRequestUseCase.Result.Outcome.ALREADY_RUNNING);
        assertThat(result.request().id()).isEqualTo(running.id());
        assertThat(requests.saved).as("второй запрос не создаётся").isZero();
    }

    @Test
    void returningARunningAnalysisCostsNoQuota() {
        // BR-A25. The check sits before the charge for exactly this: paying for an answer that is
        // "your own request is still going" is the thing this increment removes.
        var quotas = new CountingQuotas();

        submit(useCase(Requests.withActive(runningRequest(Fixtures.USER_ID)), quotas));

        assertThat(quotas.consumed).isZero();
    }

    @Test
    void theQuestionIsTheSamePairThatFreshnessUses() {
        // P1: the same normalized query and the same resolved parameters — including the profile the
        // use case filled in — so "the same question" has one definition, not two.
        var requests = Requests.empty();

        submit(useCase(requests, new CountingQuotas()));

        assertThat(requests.askedQuery).isEqualTo("квантовые вычисления");
        assertThat(requests.askedParameters.methodologyProfileId()).isEqualTo(Fixtures.PROFILE_ID);
    }

    @Test
    void aColleaguesRunningAnalysisIsJoinedRatherThanDuplicated() {
        // BR-A47. Раньше правило было пользовательским, и разница между «повезло» и «не повезло»
        // измерялась секундами: опоздавший на минуту получал готовый отчёт, нажавший одновременно
        // оплачивал второй сбор того же корпуса — с того же счёта организации.
        var colleague = runningRequest(OTHER_USER);
        var requests = Requests.withActive(colleague);
        var quotas = new CountingQuotas();

        var result = submit(useCase(requests, quotas));

        assertThat(result.outcome()).isEqualTo(SubmitResearchRequestUseCase.Result.Outcome.ALREADY_RUNNING);
        assertThat(result.request().id()).isEqualTo(colleague.id());
        assertThat(requests.saved).isZero();
        assertThat(quotas.consumed)
                .as("присоединение к чужому расчёту не стоит слота")
                .isZero();
    }

    @Test
    void anotherOrganisationsRunningAnalysisDoesNotCount() {
        // Граница осталась там же, где видимость отчёта: чужой банк — чужой расчёт, и знать о нём
        // аналитик не должен.
        var stranger = runningRequest(OTHER_USER, UUID.fromString("88888888-8888-4888-8888-888888888888"));
        var requests = Requests.withActive(stranger);

        var result = submit(useCase(requests, new CountingQuotas()));

        assertThat(requests.askedOrganization).isEqualTo(Fixtures.ORGANIZATION_ID);
        assertThat(result.outcome()).isEqualTo(SubmitResearchRequestUseCase.Result.Outcome.ACCEPTED);
        assertThat(requests.saved).isOne();
    }

    @Test
    void anIdempotentReplayStillWinsOverTheRunningAnalysis() {
        // P3, first half: the key is the client's explicit statement that this is the same call.
        var byKey = runningRequest(Fixtures.USER_ID);
        var requests = new Requests(runningRequest(Fixtures.USER_ID), null, byKey);

        var result = useCase(requests, new CountingQuotas())
                .submit(Fixtures.requester(), "Квантовые Вычисления", Fixtures.parameters(), "ключ-клиента");

        assertThat(result.outcome()).isEqualTo(SubmitResearchRequestUseCase.Result.Outcome.IDEMPOTENT_REPLAY);
        assertThat(result.request().id()).isEqualTo(byKey.id());
    }

    @Test
    void aRunningAnalysisOutranksAFinishedOne() {
        // P3, second half: if a recomputation was started, the finished report has already been
        // judged stale — answering with it would undo the very action the analyst took.
        var running = runningRequest(Fixtures.USER_ID);
        var requests = new Requests(running, Fixtures.pendingRequest(), null);

        var result = submit(useCase(requests, new CountingQuotas()));

        assertThat(result.outcome()).isEqualTo(SubmitResearchRequestUseCase.Result.Outcome.ALREADY_RUNNING);
        assertThat(result.request().id()).isEqualTo(running.id());
        assertThat(requests.freshnessConsulted)
                .as("до проверки свежести дело не доходит")
                .isFalse();
    }

    @Test
    void withNothingRunningTheOrdinaryPathIsUnchanged() {
        var requests = Requests.empty();
        var quotas = new CountingQuotas();

        var result = submit(useCase(requests, quotas));

        assertThat(result.outcome()).isEqualTo(SubmitResearchRequestUseCase.Result.Outcome.ACCEPTED);
        assertThat(requests.saved).isOne();
        assertThat(quotas.consumed).isOne();
    }

    @Test
    void everyOutcomeHasItsOwnNameOnTheWire() {
        // BR-A26. `fromCache` alone cannot carry this: it is one bit over four situations, and three
        // of them are true. Asserting on it would only restate the enum — the name the client reads
        // is what has to differ, and it has to keep differing.
        var names = java.util.Arrays.stream(SubmitResearchRequestUseCase.Result.Outcome.values())
                .map(SubmitResearchRequestUseCase.Result.Outcome::wireName)
                .toList();

        assertThat(names).doesNotHaveDuplicates().doesNotContainNull();
        assertThat(SubmitResearchRequestUseCase.Result.Outcome.ALREADY_RUNNING.wireName())
                .isNotEqualTo(SubmitResearchRequestUseCase.Result.Outcome.REUSED_FRESH_RESULT.wireName());
    }

    @Test
    void theSameDirectionWithOtherParametersIsADifferentQuestion() {
        // Ten trends over three years and fifteen over seven are not the same analysis, and the
        // running one cannot answer for both. Same definition as the freshness lookup uses.
        var requests = Requests.withActive(runningRequest(Fixtures.USER_ID));

        var result = useCase(requests, new CountingQuotas())
                .submit(
                        Fixtures.requester(),
                        "Квантовые Вычисления",
                        new AnalysisParameters(10, 7, java.util.Set.of(), 0.0, false, Fixtures.PROFILE_ID, null),
                        null);

        assertThat(result.outcome()).isEqualTo(SubmitResearchRequestUseCase.Result.Outcome.ACCEPTED);
        assertThat(requests.saved).isOne();
    }

    @Test
    void aForcedRecomputationDoesNotReuseAFinishedReport() {
        // BR-A39. Без этого второй отчёт направления не появится, пока не истечёт TTL — сутки по
        // умолчанию, — и вся половина продукта, которая сравнивает версии, ждёт сутки вместе с ним.
        var requests = new Requests(null, Fixtures.pendingRequest(), null);

        var result = useCase(requests, new CountingQuotas())
                .submit(Fixtures.requester(), "Квантовые Вычисления", Fixtures.parameters(), null, true);

        assertThat(result.outcome()).isEqualTo(SubmitResearchRequestUseCase.Result.Outcome.ACCEPTED);
        assertThat(requests.saved).isOne();
    }

    @Test
    void aForcedRecomputationStillPaysForItself() {
        // BR-A40. Принудительность — заказ настоящей работы, а не освобождение от платы за неё.
        var quotas = new CountingQuotas();

        useCase(new Requests(null, Fixtures.pendingRequest(), null), quotas)
                .submit(Fixtures.requester(), "Квантовые Вычисления", Fixtures.parameters(), null, true);

        assertThat(quotas.consumed).isOne();
    }

    @Test
    void aForcedRecomputationDoesNotStartASecondParallelRun() {
        // P5: пропускается только проверка свежести. Идущий анализ и есть тот пересчёт, который
        // просят, — запускать второй незачем.
        var requests = Requests.withActive(runningRequest(Fixtures.USER_ID));
        var quotas = new CountingQuotas();

        var result = useCase(requests, quotas)
                .submit(Fixtures.requester(), "Квантовые Вычисления", Fixtures.parameters(), null, true);

        assertThat(result.outcome()).isEqualTo(SubmitResearchRequestUseCase.Result.Outcome.ALREADY_RUNNING);
        assertThat(requests.saved).isZero();
        assertThat(quotas.consumed).isZero();
    }

    @Test
    void anIdempotencyKeyStillOutranksAForcedRecomputation() {
        // P5, вторая половина: ключ — защита от двойного клика, и повтор того же вызова не должен
        // запускать вторую работу только потому, что в нём стоит флаг.
        var byKey = runningRequest(Fixtures.USER_ID);
        var requests = new Requests(null, null, byKey);

        var result = useCase(requests, new CountingQuotas())
                .submit(Fixtures.requester(), "Квантовые Вычисления", Fixtures.parameters(), "ключ", true);

        assertThat(result.outcome()).isEqualTo(SubmitResearchRequestUseCase.Result.Outcome.IDEMPOTENT_REPLAY);
    }

    @Test
    void withoutTheFlagAFinishedReportIsStillReused() {
        // BR-A41: по умолчанию поведение прежнее. Иначе каждый обычный вопрос стоил бы полного
        // анализа и списания квоты.
        var requests = new Requests(null, Fixtures.pendingRequest(), null);

        var result = submit(useCase(requests, new CountingQuotas()));

        assertThat(result.outcome()).isEqualTo(SubmitResearchRequestUseCase.Result.Outcome.REUSED_FRESH_RESULT);
        assertThat(requests.saved).isZero();
    }

    @Test
    void theCacheIsAskedOnBehalfOfTheOrganisation() {
        // BR-A43. Без этого проверка свежести отвечала «готовый отчёт есть, вот он» идентификатором,
        // который читателю потом отказывали, — причём отчёт мог принадлежать другому банку.
        var requests = Requests.empty();

        submit(useCase(requests, new CountingQuotas()));

        // Именно поле проверки свежести: одно поле на два запроса означало бы, что утверждение
        // проходит, даже если организацию получил только один из них.
        assertThat(requests.freshnessOrganization).isEqualTo(Fixtures.ORGANIZATION_ID);
    }
}
