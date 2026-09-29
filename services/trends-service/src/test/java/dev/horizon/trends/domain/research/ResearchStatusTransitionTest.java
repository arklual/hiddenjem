package dev.horizon.trends.domain.research;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Walks the full 7×7 transition table.
 *
 * <p>An exhaustive table test, not a handful of happy paths: the state machine is the contract the
 * whole saga rests on, and the interesting failures are the transitions somebody forgot to forbid.
 * The expectation is written out independently of the production table so that a change to
 * {@link ResearchStatus} cannot quietly bless itself.
 */
class ResearchStatusTransitionTest {

    /** Independent restatement of the spec (domain-model §4.2), deliberately not derived from ALLOWED. */
    private static final Map<ResearchStatus, Set<ResearchStatus>> EXPECTED = Map.of(
            ResearchStatus.PENDING, Set.of(ResearchStatus.COLLECTING, ResearchStatus.FAILED, ResearchStatus.CANCELLED),
            ResearchStatus.COLLECTING,
                    Set.of(ResearchStatus.ANALYZING, ResearchStatus.FAILED, ResearchStatus.CANCELLED),
            ResearchStatus.ANALYZING,
                    Set.of(ResearchStatus.ASSEMBLING, ResearchStatus.FAILED, ResearchStatus.CANCELLED),
            ResearchStatus.ASSEMBLING,
                    Set.of(ResearchStatus.COMPLETED, ResearchStatus.FAILED, ResearchStatus.CANCELLED),
            ResearchStatus.COMPLETED, Set.of(),
            ResearchStatus.FAILED, Set.of(),
            ResearchStatus.CANCELLED, Set.of());

    static Stream<Arguments> allPairs() {
        return Stream.of(ResearchStatus.values())
                .flatMap(from -> Stream.of(ResearchStatus.values()).map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} → {1}")
    @MethodSource("allPairs")
    @DisplayName("каждая из 49 пар статусов разрешена ровно тогда, когда этого требует спецификация")
    void everyPairMatchesTheSpecification(ResearchStatus from, ResearchStatus to) {
        assertThat(from.canTransitionTo(to))
                .as("%s → %s", from, to)
                .isEqualTo(EXPECTED.get(from).contains(to));
    }

    @Test
    void theTableCoversAllSevenStatuses() {
        assertThat(ResearchStatus.values()).hasSize(7);
        assertThat(EXPECTED).hasSize(7);
    }

    @ParameterizedTest
    @EnumSource(ResearchStatus.class)
    void nullIsNeverALegalTarget(ResearchStatus from) {
        assertThat(from.canTransitionTo(null)).isFalse();
    }

    @Test
    void terminalStatusesAreExactlyTheOnesWithNoOutgoingTransitions() {
        for (ResearchStatus status : ResearchStatus.values()) {
            assertThat(status.isTerminal())
                    .as("%s терминален ⟺ из него нет переходов", status)
                    .isEqualTo(EXPECTED.get(status).isEmpty());
            assertThat(status.isActive()).isNotEqualTo(status.isTerminal());
        }
    }

    @Test
    void everyActiveStatusCanAlwaysFailAndBeCancelled() {
        List<ResearchStatus> active = Stream.of(ResearchStatus.values())
                .filter(ResearchStatus::isActive)
                .toList();
        assertThat(active).hasSize(4);
        for (ResearchStatus status : active) {
            assertThat(status.canTransitionTo(ResearchStatus.FAILED)).isTrue();
            assertThat(status.canTransitionTo(ResearchStatus.CANCELLED)).isTrue();
        }
    }

    @Test
    void noStatusCanTransitionToItself() {
        // Self-transitions are handled as idempotent no-ops by the aggregate, never by the table.
        for (ResearchStatus status : ResearchStatus.values()) {
            assertThat(status.canTransitionTo(status)).isFalse();
        }
    }
}
