package dev.horizon.trends.adapter.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import dev.horizon.trends.application.port.TermTraceExplainer;

@DisplayName("Трассировка термина: один повтор анализа на один вопрос")
class SingleFlightTermTraceExplainerTest {

    private static final UUID SNAPSHOT = UUID.fromString("01a0e331-3056-7000-9721-9ed8736c10a4");
    private static final TermTraceExplainer.Explanation ANSWER =
            new TermTraceExplainer.Explanation(List.of("extracted", "ranked"), List.of());

    private static TermTraceExplainer.Request request(String... terms) {
        return new TermTraceExplainer.Request("req-1", 1, SNAPSHOT, "q", "q", null, null, List.of(terms));
    }

    /** Часы, которые двигает тест. */
    private static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-09-28T08:00:00Z");

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    @DisplayName("повторы, пришедшие во время прогона, ждут его, а не запускают свой")
    void concurrentCallsShareOneRun() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        TermTraceExplainer slow = req -> {
            runs.incrementAndGet();
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ANSWER;
        };
        var explainer = new SingleFlightTermTraceExplainer(slow, new TestClock());
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<TermTraceExplainer.Explanation> first = pool.submit(() -> explainer.explain(request("firewall")));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            Future<TermTraceExplainer.Explanation> second = pool.submit(() -> explainer.explain(request("Firewall ")));
            Future<TermTraceExplainer.Explanation> third = pool.submit(() -> explainer.explain(request("firewall")));
            Thread.sleep(100);
            release.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS)).isSameAs(ANSWER);
            assertThat(second.get(5, TimeUnit.SECONDS)).isSameAs(ANSWER);
            assertThat(third.get(5, TimeUnit.SECONDS)).isSameAs(ANSWER);
            assertThat(runs).hasValue(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("готовый ответ помнится: срез заморожен, повтор дал бы то же самое")
    void answerIsRemembered() {
        AtomicInteger runs = new AtomicInteger();
        var explainer = new SingleFlightTermTraceExplainer(req -> {
            runs.incrementAndGet();
            return ANSWER;
        }, new TestClock());

        explainer.explain(request("firewall", "canary tokens"));
        explainer.explain(request("canary tokens", "firewall"));

        assertThat(runs).hasValue(1);
    }

    @Test
    @DisplayName("отказ помнится минуту — повторы шлюза не перезапускают прогон, а позже можно снова")
    void failureIsRememberedBriefly() {
        AtomicInteger runs = new AtomicInteger();
        var clock = new TestClock();
        var explainer = new SingleFlightTermTraceExplainer(req -> {
            runs.incrementAndGet();
            throw new TermTraceExplainer.ExplanationUnavailableException("движок не ответил", null);
        }, clock);

        assertThatThrownBy(() -> explainer.explain(request("firewall")))
                .isInstanceOf(TermTraceExplainer.ExplanationUnavailableException.class);
        assertThatThrownBy(() -> explainer.explain(request("firewall")))
                .isInstanceOf(TermTraceExplainer.ExplanationUnavailableException.class);
        assertThat(runs).hasValue(1);

        clock.now = clock.now.plus(SingleFlightTermTraceExplainer.FAILURE_TTL).plusSeconds(1);
        assertThatThrownBy(() -> explainer.explain(request("firewall")))
                .isInstanceOf(TermTraceExplainer.ExplanationUnavailableException.class);
        assertThat(runs).hasValue(2);
    }

    @Test
    @DisplayName("разные термины — разные вопросы")
    void differentTermsAreDifferentQuestions() {
        AtomicInteger runs = new AtomicInteger();
        var explainer = new SingleFlightTermTraceExplainer(req -> {
            runs.incrementAndGet();
            return ANSWER;
        }, new TestClock());

        explainer.explain(request("firewall"));
        explainer.explain(request("canary tokens"));

        assertThat(runs).hasValue(2);
    }

    @Test
    @DisplayName("без ожидания: первый вопрос запускает прогон в фоне, опросы его не множат")
    void pollingStartsOneBackgroundRunAndReturnsItsAnswer() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        TermTraceExplainer slow = req -> {
            runs.incrementAndGet();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ANSWER;
        };
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            var explainer = new SingleFlightTermTraceExplainer(slow, new TestClock(), pool);

            // Ни один опрос не ждёт движка — именно это не даёт прокси оборвать соединение.
            assertThat(explainer.explainIfReady(request("firewall"))).isEmpty();
            assertThat(explainer.explainIfReady(request("Firewall"))).isEmpty();

            release.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();

            assertThat(explainer.explainIfReady(request("firewall"))).containsSame(ANSWER);
            assertThat(runs).hasValue(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("без ожидания: отказ фонового прогона доходит до опроса, а не теряется")
    void pollingSurfacesAFailedRun() {
        var explainer = new SingleFlightTermTraceExplainer(req -> {
            throw new TermTraceExplainer.ExplanationUnavailableException("движок не ответил", null);
        }, new TestClock());

        assertThatThrownBy(() -> explainer.explainIfReady(request("firewall")))
                .isInstanceOf(TermTraceExplainer.ExplanationUnavailableException.class);
    }

    @Test
    @DisplayName("Spring поднимает обёртку и отдаёт её вместо прямого клиента")
    void springBuildsTheWrapper() {
        try (var context = new AnnotationConfigApplicationContext()) {
            // Готовым объектом, а не классом: иначе Spring разберёт вложенную конфигурацию клиента.
            context.getBeanFactory().registerSingleton("http", Mockito.mock(HttpTermTraceExplainer.class));
            context.register(SingleFlightTermTraceExplainer.class);
            context.refresh();

            assertThat(context.getBean(TermTraceExplainer.class)).isInstanceOf(SingleFlightTermTraceExplainer.class);
        }
    }
}
