package dev.horizon.trends.adapter.web.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import dev.horizon.trends.support.Fixtures;

/**
 * Authorship in the request view (BR-A53, BR-A57).
 *
 * <p>Both fields regress silently: {@code @JsonInclude(NON_NULL)} simply drops whatever is null, so a
 * mapper that stopped filling them would produce a list where every row is unsigned and nothing would
 * fail. The opposite mistake is worse — filling them on the single-request view publishes the author
 * of every analysis to a page reached by link.
 */
class RequestAuthorViewTest {

    private static final UUID SOMEBODY_ELSE = UUID.fromString("99999999-9999-4999-8999-999999999999");

    private final ResearchViewMapper mapper = new ResearchViewMapper(Clock.fixed(Fixtures.NOW, ZoneOffset.UTC));

    @Test
    void theListSignsYourOwnRowAsYours() {
        var view = mapper.toView(Fixtures.pendingRequest(), Fixtures.USER_ID);

        assertThat(view.mine()).isTrue();
        assertThat(view.requestedBy()).isEqualTo(Fixtures.USER_ID);
    }

    @Test
    void theListSignsAColleaguesRowAsTheirs() {
        var view = mapper.toView(Fixtures.pendingRequest(), SOMEBODY_ELSE);

        assertThat(view.mine()).isFalse();
        assertThat(view.requestedBy())
                .as("идентификатор автора — то, по чему клиент спросит имя")
                .isEqualTo(Fixtures.USER_ID);
    }

    @Test
    void theSingleRequestViewSaysNothingAboutAuthorship() {
        // На эту страницу приходят по ссылке на конкретный расчёт: вопрос «чей он» там не стоит, а
        // ответ на незаданный вопрос — это разглашение без причины.
        var view = mapper.toView(Fixtures.pendingRequest());

        assertThat(view.mine()).isNull();
        assertThat(view.requestedBy()).isNull();
    }
}
