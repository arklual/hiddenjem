package dev.horizon.ingestion.connector.support;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Пауза между обращениями к одному хосту — {@code Crawl-delay} его {@code robots.txt}.
 *
 * <p>Ограничитель частоты источника здесь не годится: у него есть запас на всплеск (три запроса
 * подряд), и площадка, попросившая десять секунд между запросами, первые три получала бы разом. Для
 * документированных API всплеск допустим — их условия говорят о частоте в минуту; для сайта,
 * записавшего паузу в {@code robots.txt}, — нет.
 */
public final class CrawlDelay {

    private final Duration delay;
    private final Map<String, Long> lastByHost = new ConcurrentHashMap<>();

    public CrawlDelay(Duration delay) {
        this.delay = delay;
    }

    /** Дождаться, пока с прошлого обращения к хосту пройдёт пауза, и отметить новое обращение. */
    public void await(String host) {
        long nanos = delay.toNanos();
        long now = System.nanoTime();
        // Слот резервируется атомарно: два потока, пришедшие к одному хосту, получают моменты через
        // паузу друг от друга, а к разным хостам — не ждут друг друга вовсе.
        long slot = lastByHost.merge(host, now, (previous, current) -> Math.max(current, previous + nanos));
        if (slot > now) {
            try {
                Thread.sleep(Duration.ofNanos(slot - now));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ConnectorException.Retryable(host, 0, "Interrupted while honouring Crawl-delay");
            }
        }
    }
}
