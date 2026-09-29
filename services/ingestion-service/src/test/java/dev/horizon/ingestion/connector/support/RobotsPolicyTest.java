package dev.horizon.ingestion.connector.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BR-C3 requires the terms of use of a source to be honoured, and names {@code robots.txt} among
 * them. Until this class existed the requirement was met by a deployment variable named
 * {@code HORIZON_INGESTION_RESPECT_ROBOTS} that nothing read — a control that exists only on a
 * compliance review is worse than an absent one.
 *
 * <p>Only the RSS connector consults the policy, because only it walks URLs an operator typed into
 * configuration. The documented APIs are governed by their own terms, honoured through a contactable
 * User-Agent and a per-source rate limit.
 */
class RobotsPolicyTest {

    private static final String AGENT = "HorizonBot/1.0 (+https://horizon.dev; mailto:ops@horizon.dev)";

    private static RobotsPolicy policyServing(String body) {
        return new RobotsPolicy(uri -> body);
    }

    @Test
    @DisplayName("площадка без robots.txt ничего не запрещает")
    void anAbsentFileImposesNoRestriction() {
        // Отсутствие файла — не запрет: иначе сбор зависел бы от доступности того, чего у
        // большинства площадок нет.
        assertThat(new RobotsPolicy(uri -> null).allows(URI.create("https://news.example/feed.xml"), AGENT))
                .isTrue();
    }

    @Test
    @DisplayName("недоступный robots.txt не превращается в запрет")
    void aFailingFetchImposesNoRestriction() {
        var policy = new RobotsPolicy(uri -> {
            throw new IllegalStateException("сеть недоступна");
        });

        assertThat(policy.allows(URI.create("https://news.example/feed.xml"), AGENT))
                .isTrue();
    }

    @Test
    @DisplayName("запрет для всех агентов соблюдается")
    void aDisallowForEveryoneIsHonoured() {
        var policy = policyServing("User-agent: *\nDisallow: /feed");

        assertThat(policy.allows(URI.create("https://news.example/feed.xml"), AGENT))
                .isFalse();
        assertThat(policy.allows(URI.create("https://news.example/blog.xml"), AGENT))
                .isTrue();
    }

    @Test
    @DisplayName("правило для нашего агента важнее правила для всех")
    void theGroupForOurTokenWins() {
        // Токен читается из User-Agent до косой черты: `HorizonBot/1.0 (…)` → `horizonbot`.
        var policy = policyServing("User-agent: *\nDisallow: /\n\nUser-agent: HorizonBot\nDisallow:");

        assertThat(policy.allows(URI.create("https://news.example/feed.xml"), AGENT))
                .isTrue();
    }

    @Test
    @DisplayName("побеждает самое длинное совпадение, а при равенстве — разрешение")
    void theLongestMatchDecides() {
        var policy = policyServing("User-agent: *\nDisallow: /blog\nAllow: /blog/tech");

        assertThat(policy.allows(URI.create("https://news.example/blog/other"), AGENT))
                .isFalse();
        assertThat(policy.allows(URI.create("https://news.example/blog/tech/rss"), AGENT))
                .isTrue();
    }

    @Test
    @DisplayName("подстановки * и $ разбираются")
    void wildcardsAreUnderstood() {
        var policy = policyServing("User-agent: *\nDisallow: /*.pdf$");

        assertThat(policy.allows(URI.create("https://news.example/papers/report.pdf"), AGENT))
                .isFalse();
        assertThat(policy.allows(URI.create("https://news.example/papers/report.pdf.xml"), AGENT))
                .isTrue();
    }

    @Test
    @DisplayName("robots.txt запрашивается один раз на хост")
    void theFileIsFetchedOncePerHost() {
        // Повторный запрос перед каждой лентой удваивал бы нагрузку на площадку, которую мы как раз
        // стараемся уважать.
        List<URI> requested = new ArrayList<>();
        var policy = new RobotsPolicy(uri -> {
            requested.add(uri);
            return "User-agent: *\nDisallow: /private";
        });

        policy.allows(URI.create("https://news.example/a.xml"), AGENT);
        policy.allows(URI.create("https://news.example/b.xml"), AGENT);
        policy.allows(URI.create("https://other.example/c.xml"), AGENT);

        assertThat(requested)
                .containsExactly(
                        URI.create("https://news.example/robots.txt"), URI.create("https://other.example/robots.txt"));
    }

    @Test
    @DisplayName("комментарии и пустые строки не сбивают разбор")
    void commentsAndBlankLinesAreIgnored() {
        var policy = policyServing("# наш файл\n\nUser-agent: *   # для всех\nDisallow: /feed # ленты\n");

        assertThat(policy.allows(URI.create("https://news.example/feed.xml"), AGENT))
                .isFalse();
    }
}
