package dev.horizon.ingestion.application;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import dev.horizon.ingestion.config.QueryExpansionProperties;
import dev.horizon.ingestion.domain.event.CorpusCollectionFailed;
import dev.horizon.ingestion.domain.port.CollectionRequest;
import dev.horizon.ingestion.domain.port.NormalizingSourceConnector;
import dev.horizon.ingestion.domain.port.QueryExpander;
import dev.horizon.ingestion.domain.run.RunMode;
import dev.horizon.ingestion.domain.snapshot.CorpusSnapshot;
import dev.horizon.platform.common.id.Uuid7;

/**
 * Handles {@code CollectDomainCorpus}: fan out to every eligible connector, tolerate the ones that
 * fail, deduplicate what comes back, and publish exactly one outcome (UC-02 step 5, FR-04.*).
 *
 * <p>Shape of the flow, and why:
 *
 * <ol>
 *   <li><b>Cheap idempotency pre-check.</b> A redelivered command must not re-crawl six sources.
 *   <li><b>Parallel fan-out, sequential within a source, over a budget split between sources.</b>
 *       Each source runs its query-direction request and then its narrow phrasings in its own
 *       virtual thread, so its rate limit stays as honest as before while the collection lasts as
 *       long as the slowest source rather than the sum of all of them (analysis 104). Documents are
 *       merged in source order, not completion order, so the snapshot stays reproducible. The
 *       budget is handed out in shares rather than first-come: without that one prolific source spent the whole
 *       {@code maxDocuments} allowance before the others were asked at all, and the corpus mix the
 *       methodology relies on — papers, patents, code, news — existed only on paper. Deep research
 *       runs alongside both phases: the narrow phrasings start as soon as every other source has
 *       answered the query-direction request, and the collection ends when both are done.
 *   <li><b>Per-connector isolation.</b> {@link ConnectorCollectionService} returns an outcome
 *       instead of throwing, so a failure degrades the result to {@code partial} rather than
 *       cancelling it (BR-C7, BRULE-8).
 *   <li><b>One terminal event.</b> Emitted under the {@code (requestId, attempt)} claim, so a
 *       duplicate command produces no duplicate documents and no second event (FR-05.6).
 * </ol>
 */
@Service
public class CollectDomainCorpusUseCase {

    private static final Logger log = LoggerFactory.getLogger(CollectDomainCorpusUseCase.class);

    /**
     * Источники, которым узкие запросы не задаются. RSS не ищет на стороне сервера — каждая
     * формулировка скачала бы те же ленты заново; эталонный корпус отвечает на любой запрос одним и
     * тем же. Глубокое исследование само решает, что спросить, и шесть минут на каждую формулировку
     * в сагу не поместились бы (разбор 102).
     */
    private static final Set<String> NO_EXPANSION_SOURCES = Set.of("rss", "deepresearch");

    /**
     * Источники, которых вторая фаза не ждёт. Глубокое исследование длится весь свой бюджет — шесть
     * минут в быстром режиме, двадцать в качественном, — а узких запросов ему не задают. Пока вторая
     * фаза ждала его, узкие запросы начинались на седьмой минуте, а в качественном режиме — на
     * двадцать первой, когда дедлайн расширения давно истёк и не спрашивалось ничего. Теперь они
     * идут, пока исследование ещё читает, и сбор кончается, когда кончились оба.
     */
    private static final Set<String> RUNS_ALONGSIDE_SOURCES = Set.of("deepresearch");

    /**
     * Доля запроса направления у веб-корпуса (разбор 110). Поиск локальный и стоит миллисекунды, а
     * страницы, на которых сборщики прочитали сигналы направления, — доказательная база имён,
     * которые движок получит вторым источником кандидатов. При обычной доле в семь десятков
     * документов большая их часть в корпус анализа не попадала.
     */
    private static final int WEBCORPUS_BASE_SHARE = 400;

    private static int baseShare(String sourceId, int share) {
        return "webcorpus".equals(sourceId) ? Math.max(share, WEBCORPUS_BASE_SHARE) : share;
    }

    private final ConnectorCatalog catalog;
    private final ConnectorCollectionService collectionService;
    private final CorpusResultPublisher resultPublisher;
    private final Uuid7 uuid7;
    private final QueryExpander expander;
    private final QueryExpansionProperties expansion;
    private final CorpusTranslation translation;
    private final CorpusRelevance relevance;

    public CollectDomainCorpusUseCase(
            ConnectorCatalog catalog,
            ConnectorCollectionService collectionService,
            CorpusResultPublisher resultPublisher,
            Uuid7 uuid7) {
        this(
                catalog,
                collectionService,
                resultPublisher,
                uuid7,
                QueryExpander.NONE,
                QueryExpansionProperties.disabled(),
                CorpusTranslation.NONE);
    }

    public CollectDomainCorpusUseCase(
            ConnectorCatalog catalog,
            ConnectorCollectionService collectionService,
            CorpusResultPublisher resultPublisher,
            Uuid7 uuid7,
            QueryExpander expander,
            QueryExpansionProperties expansion) {
        this(catalog, collectionService, resultPublisher, uuid7, expander, expansion, CorpusTranslation.NONE);
    }

    public CollectDomainCorpusUseCase(
            ConnectorCatalog catalog,
            ConnectorCollectionService collectionService,
            CorpusResultPublisher resultPublisher,
            Uuid7 uuid7,
            QueryExpander expander,
            QueryExpansionProperties expansion,
            CorpusTranslation translation) {
        this(catalog, collectionService, resultPublisher, uuid7, expander, expansion, translation, CorpusRelevance.NONE);
    }

    @Autowired
    public CollectDomainCorpusUseCase(
            ConnectorCatalog catalog,
            ConnectorCollectionService collectionService,
            CorpusResultPublisher resultPublisher,
            Uuid7 uuid7,
            QueryExpander expander,
            QueryExpansionProperties expansion,
            CorpusTranslation translation,
            CorpusRelevance relevance) {
        this.catalog = catalog;
        this.collectionService = collectionService;
        this.resultPublisher = resultPublisher;
        this.uuid7 = uuid7;
        this.expander = expander;
        this.expansion = expansion;
        this.translation = translation;
        this.relevance = relevance;
    }

    public void handle(CollectDomainCorpusCommand command) {
        String idempotencyKey = command.idempotencyKey();
        if (resultPublisher.alreadyPublished(idempotencyKey)) {
            log.info("Command {} already handled — ignoring duplicate", idempotencyKey);
            return;
        }

        CollectionRequest probe = command.toCollectionRequest(command.maxDocuments());
        List<NormalizingSourceConnector> connectors = catalog.connectorsFor(probe);
        if (connectors.isEmpty()) {
            resultPublisher.publishFailed(
                    idempotencyKey,
                    command.researchRequestId(),
                    command.attempt(),
                    CorpusCollectionFailed.CODE_NO_SOURCES_ENABLED,
                    "Нет включённых источников для запрошенных классов",
                    true,
                    Map.of(
                            "sourceClasses",
                            command.sourceClasses().stream().map(Enum::name).toList()));
            return;
        }

        // Сбор параллелен по источникам и последователен внутри источника. Раньше все источники
        // шли по очереди, и сбор длился сумму их времён: на стенде 906 с чистой работы десяти
        // источников растягивались в 15 минут, из них 5,5 — глубокое исследование, во время
        // которого остальные просто ждали (разбор 106). Внутри источника порядок прежний — запрос
        // направления, затем узкие формулировки, — поэтому его собственный лимит запросов
        // соблюдается так же, как раньше: другие источники ходят на другие хосты.
        //
        // Расширение запроса моделью идёт одновременно с запросом направления: узкие формулировки
        // нужны только второй фазе, и ждать их до начала сбора незачем.
        long startedAt = System.nanoTime();
        long expansionDeadline = startedAt + expansion.timeBudget().toNanos();
        boolean expanding = expansion.active();
        int baseBudget = expanding
                ? command.maxDocuments() - (int) Math.floor(command.maxDocuments() * expansion.budgetShare())
                : command.maxDocuments();
        int share = Math.max(1, (int) Math.ceil((double) baseBudget / connectors.size()));

        List<SourceTask> tasks = new ArrayList<>(connectors.size());
        for (NormalizingSourceConnector connector : connectors) {
            tasks.add(new SourceTask(connector));
        }
        // Те, кого вторая фаза ждёт, — и только их итогами она распоряжается: поля идущих рядом
        // задач читать до их конца нельзя, а решение, зависящее от того, успело ли исследование,
        // сделало бы снимок функцией расписания потоков (ADR-0015).
        List<SourceTask> awaited = tasks.stream()
                .filter(task -> !RUNS_ALONGSIDE_SOURCES.contains(task.sourceId()))
                .toList();
        // Доля идущих рядом считается израсходованной целиком. Сколько они принесут, к началу второй
        // фазы неизвестно, а бюджет корпуса превышать нельзя; недобор исследования — десятки
        // страниц из его доли в сотни документов — второй фазе поэтому не достаётся.
        int alongsideReserve = share * (tasks.size() - awaited.size());
        Set<UUID> claimedForTranslation = ConcurrentHashMap.newKeySet();
        List<String> expansionPhrases = new ArrayList<>();
        CollectionProgress progress = new CollectionProgress(command, tasks.size());

        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<QueryExpander.Expansion> expansionFuture = pool.submit(() -> expanding
                    ? expander.expand(
                            command.query(), command.subjectTargets(), expansion.maxQueries(), expansion.languages())
                    : QueryExpander.Expansion.EMPTY);

            // Фаза 1: запрос направления, каждому источнику своя доля. Идущие рядом переводят своё
            // сами, как только закончили: вторая фаза их не ждёт, и перевести его после неё значило
            // бы отложить весь их перевод на конец сбора.
            List<Future<?>> base = new ArrayList<>();
            List<Future<?>> alongside = new ArrayList<>();
            for (SourceTask task : tasks) {
                if (awaited.contains(task)) {
                    base.add(pool.submit(() -> {
                        task.base(command.toCollectionRequest(baseShare(task.sourceId(), share)));
                        progress.answered(task.base);
                    }));
                } else {
                    alongside.add(pool.submit(() -> {
                        task.base(command.toCollectionRequest(share));
                        progress.answered(task.base);
                        translateOnce(task.base, claimedForTranslation);
                    }));
                }
            }
            QueryExpander.Expansion expanded = awaitExpansion(expansionFuture);
            expanded.queries().forEach(phrase -> expansionPhrases.add(phrase.query()));
            awaitAll(base);
            if (!expanded.queries().isEmpty()) {
                // Раскрытие модели (ТЗ §3.1): какая модель предложила какие запросы — в журнал.
                log.info(
                        "Запрос «{}» расширен моделью {}: {}",
                        command.query(),
                        expanded.model(),
                        expanded.queries().stream()
                                .map(q -> q.group() + ":" + q.language() + ":" + q.query())
                                .toList());
            }
            int collectedBase = (int)
                    awaited.stream().flatMap(t -> t.base.stream()).distinct().count();
            int leftover = Math.max(0, baseBudget - alongsideReserve - collectedBase);

            // Перевод — как только источник закончил, а не после всех: он идёт, пока долгие
            // источники ещё собирают. Документ переводится один раз, даже если его принесли двое.
            List<Future<?>> translations = new ArrayList<>();
            for (SourceTask task : awaited) {
                translations.add(pool.submit(() -> translateOnce(task.base, claimedForTranslation)));
            }

            List<Future<?>> second = new ArrayList<>();
            if (expanded.queries().isEmpty()) {
                // Без узких формулировок недобранное фазой 1 достаётся тем, кто выбрал свою долю
                // целиком: остальные источники малы, и спрашивать их снова бесполезно. Раньше это
                // делал переход остатка к следующему источнику; параллельно — второй круг.
                // Резерв узких запросов, если модель их не дала, тоже достаётся второму кругу: иначе
                // отказ модели молча урезал бы корпус на долю резерва.
                int unused = Math.max(0, command.maxDocuments() - alongsideReserve - collectedBase);
                List<SourceTask> saturated = awaited.stream()
                        .filter(t -> t.succeeded && t.base.size() >= share)
                        .toList();
                if (unused > 0 && !saturated.isEmpty()) {
                    int extra = (int) Math.ceil((double) unused / saturated.size());
                    progress.narrowStarted(saturated.size());
                    for (SourceTask task : saturated) {
                        second.add(pool.submit(() -> {
                            task.more(command.toCollectionRequest(share + extra), extra);
                            progress.narrowFinished(task.narrow);
                            translateOnce(task.narrow, claimedForTranslation);
                        }));
                    }
                }
            } else {
                // С узкими формулировками недобранное фазой 1 уходит им: стыки с соседними
                // областями ценнее повторного чтения той же выдачи.
                int expansionBudget = (expansion.reserveDocuments() > 0
                                ? expansion.reserveDocuments()
                                : command.maxDocuments() - baseBudget)
                        + leftover;
                List<SourceTask> searchable = awaited.stream()
                        .filter(t -> !NO_EXPANSION_SOURCES.contains(t.sourceId()))
                        .filter(t -> t.succeeded)
                        .toList();
                int pairs = 0;
                for (QueryExpander.ExpandedQuery phrase : expanded.queries()) {
                    for (SourceTask task : searchable) {
                        if (expansion.asks(task.sourceId(), phrase.language())) {
                            pairs++;
                        }
                    }
                }
                if (pairs > 0 && expansionBudget > 0) {
                    int portion = Math.max(1, Math.min(expansion.perQueryDocuments(), (int)
                            Math.ceil((double) expansionBudget / pairs)));
                    List<QueryExpander.ExpandedQuery> ordered = interleaveGroups(expanded.queries());
                    progress.narrowStarted(searchable.size());
                    for (SourceTask task : searchable) {
                        second.add(pool.submit(() -> {
                            task.expand(command, ordered, portion, expansionDeadline);
                            progress.narrowFinished(task.narrow);
                            translateOnce(task.narrow, claimedForTranslation);
                        }));
                    }
                }
            }
            awaitAll(second);
            awaitAll(alongside);
            awaitAll(translations);

            int asked = tasks.stream().mapToInt(t -> t.phrasesAsked).max().orElse(0);
            int expansionDocuments = (int)
                    tasks.stream().flatMap(t -> t.narrow.stream()).distinct().count();
            if (!expanded.queries().isEmpty()) {
                log.info(
                        "Узкие запросы по «{}»: спрошено до {} из {} формулировок, {} документов, отказов {}",
                        command.query(),
                        asked,
                        expanded.queries().size(),
                        expansionDocuments,
                        tasks.stream().mapToInt(t -> t.expansionFailures).sum());
            }
        }

        // Порядок документов — по порядку источников, а не по тому, кто ответил раньше: снимок
        // обязан быть функцией ответов источников, а не расписания потоков (ADR-0015).
        Set<UUID> documentIds = new LinkedHashSet<>();
        List<String> sourcesUsed = new ArrayList<>();
        List<String> unavailableSources = new ArrayList<>();
        Map<String, String> failures = new LinkedHashMap<>();
        for (SourceTask task : tasks) {
            documentIds.addAll(task.base);
            if (task.succeeded) {
                sourcesUsed.add(task.sourceId());
            } else {
                unavailableSources.add(task.sourceId());
                failures.put(task.sourceId(), task.errorCode == null ? "UNKNOWN" : task.errorCode);
            }
        }
        for (SourceTask task : tasks) {
            documentIds.addAll(task.narrow);
        }
        // Остаток перевода: документы, которые никто не взял по дороге (например, их источник
        // закончил последним). Уже переведённые хранилище пропустит само.
        List<UUID> untranslated = documentIds.stream()
                .filter(id -> !claimedForTranslation.contains(id))
                .toList();
        if (!untranslated.isEmpty()) {
            progress.translating(documentIds.size());
        }
        translation.translate(untranslated);
        log.info(
                "Сбор по «{}» за {} с: {} документов из {} источников",
                command.query(),
                (System.nanoTime() - startedAt) / 1_000_000_000L,
                documentIds.size(),
                sourcesUsed.size());

        if (documentIds.isEmpty()) {
            boolean everySourceDown = sourcesUsed.isEmpty();
            resultPublisher.publishFailed(
                    idempotencyKey,
                    command.researchRequestId(),
                    command.attempt(),
                    everySourceDown
                            ? CorpusCollectionFailed.CODE_ALL_SOURCES_UNAVAILABLE
                            : CorpusCollectionFailed.CODE_NO_DOCUMENTS_FOUND,
                    everySourceDown
                            ? "Ни один источник не ответил"
                            : "По запросу и окну не найдено ни одного документа",
                    // Retrying helps only if the sources were the problem.
                    everySourceDown,
                    Map.copyOf(failures));
            return;
        }

        // Отбор по фразам запроса (разбор 110): документы, где ни одна формулировка не стоит фразой,
        // в корпус анализа не идут. Перевод уже сохранён, поэтому проверяется и английский текст.
        Set<UUID> relevant = relevance.filter(
                command.query(), expansionPhrases, command.subjectTargets(), documentIds);

        CorpusSnapshot snapshot = CorpusSnapshot.assemble(
                uuid7.next(),
                command.normalizedQuery(),
                command.windowFrom(),
                command.windowTo(),
                relevant,
                sourcesUsed,
                unavailableSources);
        resultPublisher.publishCollected(idempotencyKey, command.researchRequestId(), command.attempt(), snapshot);
    }

    /**
     * Узкие запросы — каждый в каждом источнике, ответившем на запрос направления, небольшой
     * порцией.
     *
     * <p>Спрашиваются только ответившие источники: отказавший на запросе направления откажет и на
     * узких, но потратит на это по таймауту на каждую формулировку. Порция — равная доля резерва на
     * пару «формулировка × источник», не больше {@code perQueryDocuments}: узкий запрос ценен первыми
     * десятками ответов, дальше релевантность падает до шума, а равная доля не даёт первым
     * формулировкам выбрать резерв до того, как спросят последние — те, что на краю поля. Отказ по
     * узкому запросу не делает источник «недоступным» в отчёте: отчёт говорит о запросе направления,
     * узкие — дополнение к нему.
     */
    /**
     * Формулировки по кругу групп: центр, новое, стык, снова центр. Модель отдаёт их группами
     * подряд, и при исчерпании резерва или времени неспрошенным оставался бы весь хвост — стыки с
     * соседними областями, самая ценная для слабых сигналов часть.
     */
    static List<QueryExpander.ExpandedQuery> interleaveGroups(List<QueryExpander.ExpandedQuery> queries) {
        Map<String, List<QueryExpander.ExpandedQuery>> byGroup = new LinkedHashMap<>();
        for (QueryExpander.ExpandedQuery query : queries) {
            byGroup.computeIfAbsent(query.group(), group -> new ArrayList<>()).add(query);
        }
        List<QueryExpander.ExpandedQuery> ordered = new ArrayList<>(queries.size());
        for (int round = 0; ordered.size() < queries.size(); round++) {
            for (List<QueryExpander.ExpandedQuery> group : byGroup.values()) {
                if (round < group.size()) {
                    ordered.add(group.get(round));
                }
            }
        }
        return ordered;
    }

    private void translateOnce(Collection<UUID> ids, Set<UUID> claimed) {
        List<UUID> mine = new ArrayList<>();
        for (UUID id : ids) {
            if (claimed.add(id)) {
                mine.add(id);
            }
        }
        if (!mine.isEmpty()) {
            translation.translate(mine);
        }
    }

    private QueryExpander.Expansion awaitExpansion(Future<QueryExpander.Expansion> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return QueryExpander.Expansion.EMPTY;
        } catch (ExecutionException e) {
            // Расширение — дополнение: без него сбор идёт по запросу направления, как раньше.
            log.warn(
                    "Расширение запроса не удалось: {}",
                    e.getCause() == null ? e : e.getCause().toString());
            return QueryExpander.Expansion.EMPTY;
        }
    }

    private static void awaitAll(List<Future<?>> futures) {
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException e) {
                // Задача источника сама превращает отказ в исход (BRULE-8); сюда доходит только
                // ошибка программы — она не должна отменять уже собранное другими источниками.
                log.error("Задача сбора завершилась ошибкой", e.getCause());
            }
        }
    }

    /**
     * Ход сбора для саги: доля ответивших источников, затем доля закончивших уточняющие запросы.
     *
     * <p>Без него сага узнавала о сборе только по его концу, и полоса минутами стояла на нижней
     * границе стадии. Запрос направления — первые 60 % стадии, уточняющие запросы — следующие 35,
     * остаток перевода — до 95; последние проценты стадии — событие «корпус собран». Отчёт о ходе
     * не должен мешать сбору: отказ публикации только пишется в журнал.
     */
    private final class CollectionProgress {

        private static final int BASE_SHARE = 60;
        private static final int NARROW_SHARE = 35;
        private static final int TRANSLATING = 95;

        private final CollectDomainCorpusCommand command;
        private final int sources;
        private final AtomicInteger answered = new AtomicInteger();
        private final AtomicInteger narrowTotal = new AtomicInteger();
        private final AtomicInteger narrowDone = new AtomicInteger();
        private final Set<UUID> documents = ConcurrentHashMap.newKeySet();

        private CollectionProgress(CollectDomainCorpusCommand command, int sources) {
            this.command = command;
            this.sources = sources;
        }

        private void answered(Collection<UUID> ids) {
            documents.addAll(ids);
            answered.incrementAndGet();
            report();
        }

        private void narrowStarted(int count) {
            narrowTotal.set(count);
            report();
        }

        private void narrowFinished(Collection<UUID> ids) {
            documents.addAll(ids);
            narrowDone.incrementAndGet();
            report();
        }

        private synchronized void translating(int documentCount) {
            publish(TRANSLATING, "Перевод собранных документов. Документов: %d".formatted(documentCount), documentCount);
        }

        /**
         * Под замком: источники отвечают из своих потоков, и без него снимок «15 из 21» мог уйти в
         * исходящие позже «18 из 21» — счётчик на экране шёл бы назад.
         */
        private synchronized void report() {
            int done = answered.get();
            int narrowOf = narrowTotal.get();
            int narrowed = narrowDone.get();
            int percent = BASE_SHARE * done / Math.max(1, sources)
                    + (narrowOf > 0 ? NARROW_SHARE * narrowed / narrowOf : 0);
            StringBuilder message = new StringBuilder(done < sources
                    ? "Ответили %d из %d %s".formatted(done, sources, sourcesGenitive(sources))
                    : "Ответили все источники (%d)".formatted(sources));
            if (narrowOf > 0) {
                message.append(narrowed < narrowOf
                        ? ", уточняющие запросы: %d из %d".formatted(narrowed, narrowOf)
                        : ", уточняющие запросы заданы");
            }
            int documentCount = documents.size();
            message.append(". Документов: %d".formatted(documentCount));
            publish(percent, message.toString(), documentCount);
        }

        private void publish(int percent, String message, int documentCount) {
            try {
                resultPublisher.publishProgress(
                        command.researchRequestId(),
                        command.attempt(),
                        percent,
                        message,
                        answered.get(),
                        sources,
                        documentCount);
            } catch (RuntimeException e) {
                log.warn("Ход сбора по запросу {} не отправлен: {}", command.researchRequestId(), e.toString());
            }
        }

        /** «из 1 источника», «из 21 источника», но «из 5 источников». */
        private static String sourcesGenitive(int count) {
            return count % 10 == 1 && count % 100 != 11 ? "источника" : "источников";
        }
    }

    /**
     * Всё, что один источник делает в одном сборе: запрос направления, при необходимости второй
     * круг или узкие формулировки. Живёт в одном потоке, поэтому поля пишутся без синхронизации;
     * читаются они после {@link Future#get()}, что даёт нужную видимость.
     */
    private final class SourceTask {

        private final NormalizingSourceConnector connector;
        private final Set<UUID> base = new LinkedHashSet<>();
        private final Set<UUID> narrow = new LinkedHashSet<>();
        private boolean succeeded;
        private String errorCode;
        private int phrasesAsked;
        private int expansionFailures;

        private SourceTask(NormalizingSourceConnector connector) {
            this.connector = connector;
        }

        private String sourceId() {
            return connector.descriptor().id();
        }

        private void base(CollectionRequest request) {
            var run = collectionService.startRun(connector, request, RunMode.ON_DEMAND);
            // A corpus collection must not move the shared incremental cursor: it is a query-shaped
            // slice of the source, not a continuation of the scheduled crawl.
            CollectionOutcome outcome = collectionService.execute(connector, request, run, false);
            base.addAll(outcome.documentIds());
            succeeded = outcome.succeeded();
            errorCode = outcome.errorCode();
        }

        /**
         * Второй круг: тот же запрос с большей долей. Источник заново отдаёт и уже прочитанное —
         * засчитываются только новые документы и не больше {@code extra}, чтобы параллельный второй
         * круг не вышел за общий бюджет.
         */
        private void more(CollectionRequest request, int extra) {
            var run = collectionService.startRun(connector, request, RunMode.ON_DEMAND);
            CollectionOutcome outcome = collectionService.execute(connector, request, run, false);
            for (UUID id : outcome.documentIds()) {
                if (narrow.size() >= extra) {
                    break;
                }
                if (!base.contains(id)) {
                    narrow.add(id);
                }
            }
        }

        /**
         * Узкие формулировки в этом источнике по очереди, пока не кончились доля или время.
         *
         * <p>Порция — равная доля резерва на пару «формулировка × источник», не больше
         * {@code perQueryDocuments}: узкий запрос ценен первыми десятками ответов. Отказ по узкому
         * запросу не делает источник недоступным: отчёт говорит о запросе направления.
         */
        private void expand(
                CollectDomainCorpusCommand command,
                List<QueryExpander.ExpandedQuery> phrases,
                int portion,
                long deadline) {
            for (QueryExpander.ExpandedQuery phrase : phrases) {
                if (System.nanoTime() > deadline) {
                    break;
                }
                if (!CollectDomainCorpusUseCase.this.expansion.asks(sourceId(), phrase.language())) {
                    continue;
                }
                CollectionRequest request = command.toExpansionRequest(phrase.query(), phrase.language(), portion);
                if (!connector.supports(request)) {
                    continue;
                }
                phrasesAsked++;
                var run = collectionService.startRun(connector, request, RunMode.ON_DEMAND);
                CollectionOutcome outcome = collectionService.execute(connector, request, run, false);
                // С одной формулировки засчитывается не больше спрошенного: источник отдаёт
                // страницу целиком (Semantic Scholar — до тысячи записей за раз), и лишнее остаётся
                // в корпусе, но не за счёт следующих формулировок.
                int taken = 0;
                for (UUID id : outcome.documentIds()) {
                    if (taken >= portion) {
                        break;
                    }
                    if (!base.contains(id) && narrow.add(id)) {
                        taken++;
                    }
                }
                if (!outcome.succeeded()) {
                    expansionFailures++;
                }
            }
        }
    }
}
