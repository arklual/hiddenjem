package dev.horizon.ingestion.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Перевод документов на русском и китайском на английский после сбора
 * ({@code horizon.translation.*}).
 *
 * @param enabled переводить ли; без адреса сервиса моделей не включается
 * @param nlpUrl адрес сервиса моделей
 * @param batchSize документов в одном обращении к модели: крупнее — меньше накладных расходов, но
 *     дольше ответ и больше теряется при отказе
 * @param concurrency сколько пачек переводится одновременно. Модель облачная, ответ на пачку — около
 *     тридцати секунд (замер 2026-09-19: 40 текстов за 33 с), и последовательный перевод тысячи
 *     документов занял бы весь дедлайн саги
 * @param timeBudget сколько времени сбора отдано переводу. Непереведённые к концу документы уходят в
 *     анализ как есть и переводятся при следующем сборе, где они встретятся: перевод хранится
 * @param timeout сколько ждать одну пачку: китайские аннотации переводятся дольше русских, и при
 *     ста двадцати секундах терялись пачки, которые модель уже перевела
 */
@ConfigurationProperties(prefix = "horizon.translation")
public record TranslationProperties(
        boolean enabled, String nlpUrl, int batchSize, int concurrency, Duration timeBudget, Duration timeout) {

    public TranslationProperties {
        nlpUrl = nlpUrl == null ? "" : nlpUrl.trim();
        batchSize = batchSize <= 0 ? 20 : Math.min(batchSize, 40);
        concurrency = concurrency <= 0 ? 6 : Math.min(concurrency, 16);
        timeBudget = timeBudget == null ? Duration.ofMinutes(4) : timeBudget;
        timeout = timeout == null ? Duration.ofSeconds(240) : timeout;
    }

    public boolean active() {
        return enabled && !nlpUrl.isEmpty();
    }

    public static TranslationProperties disabled() {
        return new TranslationProperties(false, "", 0, 0, null, null);
    }
}
