package dev.horizon.trends.application.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
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
import dev.horizon.trends.support.Fixtures;

/**
 * Quota charged for a request that was never created must come back (BR-A22, BR-A23).
 *
 * <p>The counter lives in Redis and takes no part in the database transaction, so a rollback undoes
 * the request and leaves the charge. The analyst pays twice: once by not getting the analysis, and
 * again by meeting "quota exceeded" earlier than they should, with nothing in the interface
 * connecting the two.
 */
class QuotaRefundTest {

    private static final Instant NOW = Instant.parse("2026-03-01T10:00:00Z");

    private static class CountingQuotas implements QuotaService {
        private int consumed;
        private int refunded;

        @Override
        public void checkAndConsume(UUID userId, UUID organizationId) {
            consumed++;
        }

        @Override
        public void refund(UUID userId, UUID organizationId, Instant chargedAt) {
            refunded++;
        }

        @Override
        public Optional<QuotaBudget> remaining(UUID userId, UUID organizationId) {
            return Optional.empty();
        }
    }

    /** Fails on save when asked to, so the compensation path can be exercised. */
    private static final class Requests implements ResearchRequestRepository {
        private final boolean failOnSave;

        private Requests(boolean failOnSave) {
            this.failOnSave = failOnSave;
        }

        @Override
        public ResearchRequest save(ResearchRequest request) {
            if (failOnSave) {
                throw new IllegalStateException("база недоступна");
            }
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
        public Optional<ResearchRequest> findByIdempotencyKey(UUID userId, String idempotencyKey) {
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
        public Optional<ResearchRequest> findActive(
                UUID userId, String normalizedQuery, AnalysisParameters parameters) {
            return Optional.empty();
        }

        @Override
        public Optional<ResearchRequest> findFreshCompleted(
                String normalizedQuery, AnalysisParameters parameters, UUID organizationId, Instant since) {
            return Optional.empty();
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

    private static SubmitResearchRequestUseCase useCase(CountingQuotas quotas, boolean failOnSave) {
        return new SubmitResearchRequestUseCase(
                new Requests(failOnSave),
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

    private static void submit(SubmitResearchRequestUseCase useCase) {
        useCase.submit(Fixtures.requester(), "квантовые вычисления", Fixtures.parameters(), null);
    }

    @Test
    void aFailedRequestGivesTheQuotaBack() {
        var quotas = new CountingQuotas();

        assertThatThrownBy(() -> submit(useCase(quotas, true))).isInstanceOf(IllegalStateException.class);

        assertThat(quotas.consumed).isOne();
        assertThat(quotas.refunded)
                .as("списанное под несозданный запрос должно вернуться")
                .isOne();
    }

    @Test
    void theOriginalFailureStillReachesTheCaller() {
        // Compensation must not swallow the reason: the analyst needs to know the request did not
        // happen, and the transaction has to roll back with it.
        assertThatThrownBy(() -> submit(useCase(new CountingQuotas(), true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("база недоступна");
    }

    @Test
    void aSuccessfulRequestIsNotRefunded() {
        // The other half of the rule: refunding on success would hand out free requests.
        var quotas = new CountingQuotas();

        submit(useCase(quotas, false));

        assertThat(quotas.consumed).isOne();
        assertThat(quotas.refunded).isZero();
    }

    @Test
    void aFailureOfTheRefundItselfDoesNotReplaceTheOriginalError() {
        // The user's question is why their request failed, not that a counter could not be restored.
        // Writing this test is what exposed the defect: the first version let the refund's exception
        // escape, so an unreachable Redis would have answered "redis недоступен" to an analyst whose
        // actual problem was a database that would not accept the request.
        var brokenRefund = new CountingQuotas() {
            @Override
            public void refund(UUID userId, UUID organizationId, Instant chargedAt) {
                throw new IllegalArgumentException("redis недоступен");
            }
        };

        assertThatThrownBy(() -> submit(useCase(brokenRefund, true)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("база недоступна");
    }
}
