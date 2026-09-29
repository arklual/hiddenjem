package dev.horizon.trends.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

import dev.horizon.trends.domain.research.ResearchStatus;
import dev.horizon.trends.support.Fixtures;

/**
 * How the in-flight lookup (BR-A24) asks the database.
 *
 * <p>Two of the three parts of that promise live in arguments rather than in Java: which statuses
 * count as "still running", and that at most one row is wanted. Both are invisible to the use-case
 * tests, which stop at the port. The third part — that the earliest of several racing requests is
 * the one returned — is an {@code ORDER BY} inside the query and can only be observed against a real
 * database; here the assertion is the half that is observable, namely that the adapter takes the
 * first row rather than an arbitrary one.
 */
class ResearchRequestActiveLookupTest {

    private static final UUID ORGANIZATION = Fixtures.ORGANIZATION_ID;

    private record Call(List<String> statuses, Pageable pageable) {}

    private static Call capture(List<ResearchRequestEntity> answer) {
        var jpa = mock(ResearchRequestJpaRepository.class);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> statuses = ArgumentCaptor.forClass(List.class);
        var pageable = ArgumentCaptor.forClass(Pageable.class);
        when(jpa.findActive(any(), anyString(), anyString(), any(), any())).thenReturn(answer);

        new ResearchRequestRepositoryAdapter(jpa, new ResearchRequestMapper())
                .findActive(ORGANIZATION, "квантовые вычисления", Fixtures.parameters());

        org.mockito.Mockito.verify(jpa)
                .findActive(any(), anyString(), anyString(), statuses.capture(), pageable.capture());
        return new Call(statuses.getValue(), pageable.getValue());
    }

    @Test
    void asksOnlyForStatusesThatCanStillMoveOn() {
        // The set is derived from the aggregate, not spelled out again. If a new terminal status is
        // ever added and this list keeps it, finished requests would be reported as still running —
        // forever, and silently, because nothing else looks at this argument.
        var statuses = capture(List.of()).statuses();

        assertThat(statuses).isNotEmpty();
        for (String name : statuses) {
            assertThat(ResearchStatus.valueOf(name).isTerminal())
                    .as("статус %s терминальный, запрос с ним не идёт", name)
                    .isFalse();
        }
        for (ResearchStatus status : ResearchStatus.values()) {
            if (!status.isTerminal()) {
                assertThat(statuses).contains(status.name());
            }
        }
    }

    @Test
    void asksForAtMostOneRow() {
        // A race can leave several. Reading them all to use one would grow with the mistake it is
        // there to absorb.
        var pageable = capture(List.of()).pageable();

        assertThat(pageable.getPageSize()).isOne();
        assertThat(pageable.getPageNumber()).isZero();
    }

    @Test
    void nothingRunningIsAnAnswerRatherThanAFailure() {
        var jpa = mock(ResearchRequestJpaRepository.class);
        when(jpa.findActive(any(), anyString(), anyString(), any(), any())).thenReturn(List.of());

        var found = new ResearchRequestRepositoryAdapter(jpa, new ResearchRequestMapper())
                .findActive(ORGANIZATION, "квантовые вычисления", Fixtures.parameters());

        assertThat(found).isEmpty();
    }
}
