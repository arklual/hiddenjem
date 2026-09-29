package dev.horizon.trends.adapter.analytics;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import dev.horizon.trends.application.port.TermTraceExplainer;

/**
 * Один повтор анализа на один вопрос, сколько бы раз его ни задали.
 *
 * <p>Трассировка термина — это полный повтор анализа на замороженном срезе: 35–85 секунд и
 * гигабайты памяти движка. Одинаковый вопрос приходит несколько раз подряд не по воле аналитика:
 * шлюз повторяет упавший GET, второй щелчок, вторая вкладка. Каждый такой повтор прежде запускал в
 * движке ещё один полный прогон параллельно первому — три прогона разом выбивали analytics-api по
 * памяти, и ни один не доживал до ответа. Здесь одинаковые вопросы сводятся к одному прогону:
 *
 * <ul>
 *   <li>пока прогон идёт, повтор ждёт его результата, а не запускает свой;
 *   <li>ответ помнится: срез заморожен, и повтор дал бы тот же ответ за ту же цену;
 *   <li>отказ помнится минуту — ровно чтобы повторы шлюза после 502 не перезапускали прогон, но
 *       аналитик, нажавший «Повторить» позже, получил настоящую попытку;
 *   <li>{@link #explainIfReady} запускает прогон в фоне и сразу возвращается — клиент опрашивает,
 *       а не держит соединение минутами, которое оборвёт первый же прокси.
 * </ul>
 *
 * <p>Ключ — запуск, попытка, срез и набор терминов без учёта порядка и регистра: именно они
 * определяют ответ, всё прочее в запросе из них выводится.
 */
@Primary
@Component
public class SingleFlightTermTraceExplainer implements TermTraceExplainer {

    static final Duration ANSWER_TTL = Duration.ofHours(6);
    static final Duration FAILURE_TTL = Duration.ofMinutes(1);
    private static final int MAX_ENTRIES = 256;

    private final TermTraceExplainer delegate;
    private final Clock clock;
    /** Где идут фоновые прогоны. Виртуальные потоки: прогон почти всё время ждёт ответа движка. */
    private final Executor background;
    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    // Конструкторов несколько — остальные для тестов с часами и исполнителем, — поэтому Spring
    // указывается явно: без пометки он не выбирает ни один и не поднимает контекст.
    @Autowired
    public SingleFlightTermTraceExplainer(HttpTermTraceExplainer delegate) {
        this(delegate, Clock.systemUTC(), Executors.newVirtualThreadPerTaskExecutor());
    }

    SingleFlightTermTraceExplainer(TermTraceExplainer delegate, Clock clock) {
        this(delegate, clock, Runnable::run);
    }

    SingleFlightTermTraceExplainer(TermTraceExplainer delegate, Clock clock, Executor background) {
        this.delegate = delegate;
        this.clock = clock;
        this.background = background;
    }

    @Override
    public Explanation explain(Request request) {
        Entry entry = entryFor(request, false);
        return await(entry);
    }

    @Override
    public Optional<Explanation> explainIfReady(Request request) {
        Entry entry = entryFor(request, true);
        return entry.future.isDone() ? Optional.of(await(entry)) : Optional.empty();
    }

    /** Текущий прогон по вопросу; если его нет или он устарел — новый, здесь же или в фоне. */
    private Entry entryFor(Request request, boolean inBackground) {
        String key = keyOf(request);
        Instant now = clock.instant();
        Entry mine = new Entry(new CompletableFuture<>());
        Entry entry = entries.compute(key, (k, current) -> current == null || current.expired(now) ? mine : current);
        if (entry == mine) {
            evictExpired(now);
            if (inBackground) {
                background.execute(() -> run(mine, request));
            } else {
                run(mine, request);
            }
        }
        return entry;
    }

    private static Explanation await(Entry entry) {
        try {
            return entry.future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExplanationUnavailableException("ожидание трассировки прервано", e);
        } catch (ExecutionException | CompletionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new ExplanationUnavailableException("движок не ответил на запрос трассировки", cause);
        }
    }

    private void run(Entry entry, Request request) {
        try {
            Explanation explanation = delegate.explain(request);
            entry.settle(clock.instant().plus(ANSWER_TTL));
            entry.future.complete(explanation);
        } catch (RuntimeException e) {
            entry.settle(clock.instant().plus(FAILURE_TTL));
            entry.future.completeExceptionally(e);
        }
    }

    private void evictExpired(Instant now) {
        if (entries.size() <= MAX_ENTRIES) {
            return;
        }
        entries.entrySet().removeIf(e -> e.getValue().expired(now));
    }

    static String keyOf(Request request) {
        String terms = request.terms().stream()
                .map(term -> term.trim().toLowerCase(Locale.ROOT))
                .distinct()
                .sorted()
                .reduce((a, b) -> a + "\u0000" + b)
                .orElse("");
        return request.researchRequestId() + '|' + request.attempt() + '|' + request.snapshotId() + '|' + terms;
    }

    /** Прогон и момент, до которого его результат годен; пока прогон идёт, момента нет. */
    private static final class Entry {
        final CompletableFuture<Explanation> future;
        volatile Instant expiresAt;

        Entry(CompletableFuture<Explanation> future) {
            this.future = future;
        }

        void settle(Instant until) {
            this.expiresAt = until;
        }

        boolean expired(Instant now) {
            Instant until = expiresAt;
            return until != null && !now.isBefore(until);
        }
    }
}
