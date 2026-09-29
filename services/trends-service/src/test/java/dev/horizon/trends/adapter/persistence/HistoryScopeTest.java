package dev.horizon.trends.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import dev.horizon.trends.domain.research.ReportViewer;
import dev.horizon.trends.domain.research.ResearchStatus;
import dev.horizon.trends.support.Fixtures;

/**
 * Which requests the history list asks for (BR-A52, BR-A54, P4).
 *
 * <p>Reports, reuse and running analyses are scoped to the organisation; the history was the last
 * place asking by {@code user_id}, and therefore the place where a request joined from a colleague
 * disappeared the moment the tab closed. Which query is chosen is invisible above the adapter — the
 * use case only sees a page of results either way — so this is where the choice is pinned.
 */
class HistoryScopeTest {

    private static final UUID ORGANIZATION = Fixtures.ORGANIZATION_ID;
    private static final UUID USER = Fixtures.USER_ID;

    private final ResearchRequestJpaRepository jpa = mock(ResearchRequestJpaRepository.class);

    private ResearchRequestRepositoryAdapter adapter() {
        Page<ResearchRequestEntity> empty = new PageImpl<>(List.of());
        when(jpa.findByUserIdOrderBySubmittedAtDesc(any(), any())).thenReturn(empty);
        when(jpa.findByUserIdAndStatusOrderBySubmittedAtDesc(any(), anyString(), any()))
                .thenReturn(empty);
        when(jpa.findByOrganizationIdOrderBySubmittedAtDesc(any(), any())).thenReturn(empty);
        when(jpa.findByOrganizationIdAndStatusOrderBySubmittedAtDesc(any(), anyString(), any()))
                .thenReturn(empty);
        return new ResearchRequestRepositoryAdapter(jpa, new ResearchRequestMapper());
    }

    private static ReportViewer inBank() {
        return new ReportViewer(USER, ORGANIZATION, false);
    }

    @Test
    void byDefaultTheWholeOrganisationIsAsked() {
        // P1. Иначе присоединённый запрос по-прежнему невидим по умолчанию, и главная причина
        // инкремента не устраняется.
        adapter().findHistory(inBank(), false, null, 0, 20);

        verify(jpa).findByOrganizationIdOrderBySubmittedAtDesc(eq(ORGANIZATION), any(Pageable.class));
        verify(jpa, never()).findByUserIdOrderBySubmittedAtDesc(any(), any());
    }

    @Test
    void onlyMineNarrowsBackToTheCaller() {
        adapter().findHistory(inBank(), true, null, 0, 20);

        verify(jpa).findByUserIdOrderBySubmittedAtDesc(eq(USER), any(Pageable.class));
        verify(jpa, never()).findByOrganizationIdOrderBySubmittedAtDesc(any(), any());
    }

    @Test
    void theStatusFilterWorksInBothScopes() {
        // P6: «чьё» и «в каком состоянии» — ортогональные вопросы, и один не должен отменять другой.
        adapter().findHistory(inBank(), false, ResearchStatus.FAILED, 0, 20);
        verify(jpa)
                .findByOrganizationIdAndStatusOrderBySubmittedAtDesc(
                        eq(ORGANIZATION), eq("FAILED"), any(Pageable.class));

        adapter().findHistory(inBank(), true, ResearchStatus.FAILED, 0, 20);
        verify(jpa).findByUserIdAndStatusOrderBySubmittedAtDesc(eq(USER), eq("FAILED"), any(Pageable.class));
    }

    @Test
    void ananalystWithoutAnOrganisationSeesOnlyTheirOwn() {
        // Расширять не по чему: других видимых ему запросов не существует (P4 спеки 15). Запросить
        // по null-организации значило бы вернуть чужие строки без организации — если такие появятся.
        adapter().findHistory(ReportViewer.of(USER), false, null, 0, 20);

        verify(jpa).findByUserIdOrderBySubmittedAtDesc(eq(USER), any(Pageable.class));
        verify(jpa, never()).findByOrganizationIdOrderBySubmittedAtDesc(any(), any());
    }

    @Test
    void theOrganisationAskedForIsTheViewersOwn() {
        // Единственная защита от «показать чужой банк» — то, что организация берётся из значения
        // читателя, а не из запроса. Здесь это и проверяется.
        var other = UUID.fromString("88888888-8888-4888-8888-888888888888");

        adapter().findHistory(new ReportViewer(USER, other, false), false, null, 0, 20);

        verify(jpa).findByOrganizationIdOrderBySubmittedAtDesc(eq(other), any(Pageable.class));
        verify(jpa, never()).findByOrganizationIdOrderBySubmittedAtDesc(eq(ORGANIZATION), any());
    }

    @Test
    void thePageSizeIsClampedRatherThanTrusted() {
        adapter().findHistory(inBank(), false, null, -3, 5000);

        var pageable = org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(jpa).findByOrganizationIdOrderBySubmittedAtDesc(any(), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(100);
    }
}
