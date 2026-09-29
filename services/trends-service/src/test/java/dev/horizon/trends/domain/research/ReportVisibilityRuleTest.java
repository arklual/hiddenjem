package dev.horizon.trends.domain.research;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import dev.horizon.trends.support.Fixtures;

/**
 * Who may read a report (BR-A42, BR-A44, P1–P4).
 *
 * <p>The rule used to be owner-only while the cache that hands out report identifiers was not scoped
 * at all — so a colleague was told "the answer already exists, here it is" and then refused it, and
 * an analyst of one bank could be handed the identifier of another bank's report. Widening the rule
 * to the organisation is what makes the two agree; these tests are where that widening stops.
 */
class ReportVisibilityRuleTest {

    private static final UUID OWNER = Fixtures.USER_ID;
    private static final UUID COLLEAGUE = UUID.fromString("11111111-2222-4111-8111-111111111112");
    private static final UUID OUTSIDER = UUID.fromString("99999999-9999-4999-8999-999999999999");
    private static final UUID BANK = Fixtures.ORGANIZATION_ID;
    private static final UUID OTHER_BANK = UUID.fromString("88888888-8888-4888-8888-888888888888");

    private static ResearchRequest requestOf(UUID userId, UUID organizationId) {
        return ResearchRequest.submit(
                new RequesterRef(userId, organizationId),
                Fixtures.query(),
                Fixtures.parameters(),
                null,
                Duration.ofMinutes(10),
                Fixtures.NOW);
    }

    @Test
    void theAnalystWhoOrderedItSeesIt() {
        assertThat(requestOf(OWNER, BANK).isVisibleTo(new ReportViewer(OWNER, BANK, false)))
                .isTrue();
    }

    @Test
    void aColleagueOfTheSameOrganizationSeesIt() {
        // The organisation ordered the research and its hourly quota paid for it, so it is also the
        // unit that reads it. Owner-only visibility made a shared portfolio into a set of private
        // ones — while the radar, the portfolio map and the overlap were all built around sharing.
        assertThat(requestOf(OWNER, BANK).isVisibleTo(new ReportViewer(COLLEAGUE, BANK, false)))
                .isTrue();
    }

    @Test
    void anotherOrganizationDoesNotSeeIt() {
        assertThat(requestOf(OWNER, BANK).isVisibleTo(new ReportViewer(OUTSIDER, OTHER_BANK, false)))
                .isFalse();
    }

    @Test
    void anAnalystWithNoOrganizationSeesOnlyTheirOwn() {
        // P4, one half. The token does not require an organisation, and "no organisation" must never
        // be read as "any organisation".
        var request = requestOf(OWNER, BANK);

        assertThat(request.isVisibleTo(ReportViewer.of(OWNER))).isTrue();
        assertThat(request.isVisibleTo(ReportViewer.of(COLLEAGUE))).isFalse();
    }

    @Test
    void aReaderWithoutAnOrganizationSharesNoneWithAnybody() {
        // Only the reader side can be null: a request always has an organisation, in the domain and
        // in the schema alike. That asymmetry is the whole trap — asking the question without an
        // organisation of one's own must not turn "unaffiliated" into a membership.
        var unaffiliated = new ReportViewer(COLLEAGUE, null, false);

        assertThat(unaffiliated.sharesOrganizationWith(new RequesterRef(OWNER, BANK)))
                .isFalse();
        assertThat(unaffiliated.sharesOrganizationWith(new RequesterRef(COLLEAGUE, BANK)))
                .isFalse();
    }

    @Test
    void anAdministratorSeesEverything() {
        assertThat(requestOf(OWNER, BANK).isVisibleTo(new ReportViewer(OUTSIDER, OTHER_BANK, true)))
                .isTrue();
    }

    @Test
    void theViewerRefusesToExistWithoutAUser() {
        // A reader with no identity is not an anonymous reader — it is a bug that would make every
        // comparison here meaningless.
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ReportViewer(null, BANK, false))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void aColleagueMayReadButMayNotCancel() {
        // Чтение расширилось до организации, изменение — нет. Отмена снимает работу, за которую
        // списана квота конкретного человека, и одна проверка на два права раздала бы это молча.
        var request = requestOf(OWNER, BANK);
        var colleague = new ReportViewer(COLLEAGUE, BANK, false);

        assertThat(request.isVisibleTo(colleague)).isTrue();
        assertThat(request.isOwnedBy(colleague))
                .as("коллега не снимает чужой расчёт")
                .isFalse();
    }

    @Test
    void theAuthorOwnsTheirOwnRequest() {
        var request = requestOf(OWNER, BANK);

        assertThat(request.isOwnedBy(new ReportViewer(OWNER, BANK, false))).isTrue();
    }

    @Test
    void anAdministratorOwnsEverything() {
        assertThat(requestOf(OWNER, BANK).isOwnedBy(new ReportViewer(OUTSIDER, OTHER_BANK, true)))
                .isTrue();
    }

    @Test
    @DisplayName("запрос без организации не создаётся — на этом держится правило видимости")
    void aRequestWithoutAnOrganisationCannotExist() {
        // Не формальность и не проверка Guards. Видимость сравнивает организации читателя и
        // автора, и в этом сравнении есть охрана: «читатель без организации не делит её ни с кем»,
        // потому что Objects.equals(null, null) истинно и «без организации» стало бы членством
        // само по себе — один непривязанный пользователь читал бы отчёты другого.
        //
        // Сегодня эта охрана недостижима именно из-за запрета ниже: у автора организация есть
        // всегда. Проба это подтвердила — снятие охраны не меняет поведения ни на одном входе.
        // То есть проверять надо не охрану, а запрет, который делает её ненужной: пропадёт он —
        // охрана станет единственной, и её отсутствие станет дырой в доступе.
        assertThatThrownBy(() -> new RequesterRef(UUID.randomUUID(), null)).isInstanceOf(RuntimeException.class);
    }
}
