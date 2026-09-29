package dev.horizon.ingestion.application;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.horizon.ingestion.config.TranslationProperties;
import dev.horizon.ingestion.domain.port.DocumentTranslations;
import dev.horizon.ingestion.domain.port.DocumentTranslator;

/**
 * Перевод собранного корпуса на английский: русские и китайские документы — пачками, параллельно,
 * в пределах бюджета времени.
 *
 * <p>Зачем. Узкие запросы на русском и китайском приносят работы, которых нет в англоязычной
 * выдаче каталогов, но анализ извлекает термины из английского текста. Без перевода русский
 * документ о федеративном обучении образует тему «федеративное обучение», китайский — третью, и
 * каждая слабее настоящей. С переводом они усиливают одну.
 *
 * <p>Перевод не обязателен для отчёта: отказ модели или конец бюджета оставляют документы как есть
 * (русский текст анализ разбирает, китайский отбрасывает), а переведённое сохраняется и служит
 * следующим сборам.
 */
public class CorpusTranslation {

    private static final Logger log = LoggerFactory.getLogger(CorpusTranslation.class);

    /** Ничего не переводить: поведение до появления шага. */
    public static final CorpusTranslation NONE = new CorpusTranslation(null, null, TranslationProperties.disabled());

    private final DocumentTranslations store;
    private final DocumentTranslator translator;
    private final TranslationProperties properties;

    public CorpusTranslation(
            DocumentTranslations store, DocumentTranslator translator, TranslationProperties properties) {
        this.store = store;
        this.translator = translator;
        this.properties = properties;
    }

    /** Перевести документы корпуса, которым нужен перевод; вернуть число переведённых. */
    public int translate(Collection<UUID> documentIds) {
        if (!properties.active() || store == null || translator == null || documentIds.isEmpty()) {
            return 0;
        }
        List<DocumentTranslations.Pending> pending = store.untranslated(documentIds);
        if (pending.isEmpty()) {
            return 0;
        }
        List<List<DocumentTranslations.Pending>> batches = new ArrayList<>();
        for (int start = 0; start < pending.size(); start += properties.batchSize()) {
            batches.add(pending.subList(start, Math.min(start + properties.batchSize(), pending.size())));
        }
        long deadline = System.nanoTime() + properties.timeBudget().toNanos();
        AtomicInteger translated = new AtomicInteger();
        AtomicInteger failedBatches = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(properties.concurrency(), runnable -> {
            Thread thread = new Thread(runnable, "corpus-translation");
            thread.setDaemon(true);
            return thread;
        });
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (List<DocumentTranslations.Pending> batch : batches) {
                futures.add(pool.submit(() -> {
                    // Пачка, до которой очередь дошла после бюджета, не начинается вовсе.
                    if (System.nanoTime() > deadline) {
                        return;
                    }
                    try {
                        DocumentTranslator.Result result = translator.translate(batch);
                        for (DocumentTranslations.Translated document : result.documents()) {
                            String language = batch.stream()
                                    .filter(item -> item.id().equals(document.id()))
                                    .map(DocumentTranslations.Pending::language)
                                    .findFirst()
                                    .orElse(null);
                            store.save(document.id(), document, language, result.model());
                            translated.incrementAndGet();
                        }
                    } catch (RuntimeException e) {
                        failedBatches.incrementAndGet();
                        log.warn("Перевод пачки из {} документов не удался: {}", batch.size(), e.toString());
                    }
                }));
            }
            for (Future<?> future : futures) {
                long left = deadline - System.nanoTime();
                try {
                    // Запущенная пачка дорабатывает свой таймаут HTTP: ждать её сверх бюджета нельзя,
                    // но и прерывать запись в базу посреди пачки незачем.
                    future.get(Math.max(left, 0), TimeUnit.NANOSECONDS);
                } catch (TimeoutException e) {
                    break;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    failedBatches.incrementAndGet();
                }
            }
        } finally {
            // Без прерывания: пачка, начатая до конца бюджета, доработает в фоне и сохранит
            // перевод для следующих сборов. В этот анализ она может уже не попасть.
            pool.shutdown();
        }
        log.info(
                "Перевод корпуса: нужен {} документам, переведено {}, отказов пачек {}",
                pending.size(),
                translated.get(),
                failedBatches.get());
        return translated.get();
    }
}
