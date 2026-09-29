package dev.horizon.ingestion.domain.port;

import java.time.LocalDate;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.platform.common.util.Guards;

/**
 * What to collect: a technology domain query, a publication window and a budget.
 *
 * <p>Both the raw and the normalised query are carried. Connectors send the raw text upstream
 * (sources do their own tokenisation, and mangling it loses recall); the normalised form is used
 * for local matching — the fixture connector — and for logging, so behaviour is comparable across
 * sources.
 */
public record CollectionRequest(
        UUID researchRequestId,
        String query,
        String normalizedQuery,
        String language,
        LocalDate windowFrom,
        LocalDate windowTo,
        Set<SourceClass> sourceClasses,
        int maxDocuments,
        List<String> subjectTargets,
        /**
         * Режим анализа, ради которого идёт сбор. Коннекторы с бюджетом времени — глубокое
         * исследование — выбирают по нему свой бюджет; остальным он безразличен.
         */
        AnalysisMode mode) {

    public static final int DEFAULT_MAX_DOCUMENTS = 5000;

    private static final Pattern TERM_SPLIT = Pattern.compile("[^\\p{IsAlphabetic}\\p{IsDigit}]+");

    public CollectionRequest {
        query = Guards.requireText(query, "query").trim();
        normalizedQuery = Guards.requireText(normalizedQuery, "normalizedQuery").trim();
        Guards.requireNonNull(windowFrom, "windowFrom");
        Guards.requireNonNull(windowTo, "windowTo");
        Guards.requireArgument(!windowFrom.isAfter(windowTo), "windowFrom must not be after windowTo");
        sourceClasses = sourceClasses == null || sourceClasses.isEmpty()
                ? Set.of()
                : Collections.unmodifiableSet(EnumSet.copyOf(sourceClasses));
        Guards.requireArgument(maxDocuments > 0, "maxDocuments must be positive");
        subjectTargets = subjectTargets == null
                ? List.of()
                : subjectTargets.stream()
                        .map(target -> target == null ? "" : target.trim())
                        .filter(target -> !target.isEmpty())
                        .distinct()
                        .toList();
        language =
                language == null || language.isBlank() ? null : language.trim().toLowerCase(Locale.ROOT);
        mode = mode == null ? AnalysisMode.FAST : mode;
    }

    /**
     * Запрос быстрого режима — так строят запрос плановый обход, дозагрузка и тесты: режим анализа
     * есть только у сбора под исследование.
     */
    public CollectionRequest(
            UUID researchRequestId,
            String query,
            String normalizedQuery,
            String language,
            LocalDate windowFrom,
            LocalDate windowTo,
            Set<SourceClass> sourceClasses,
            int maxDocuments,
            List<String> subjectTargets) {
        this(
                researchRequestId,
                query,
                normalizedQuery,
                language,
                windowFrom,
                windowTo,
                sourceClasses,
                maxDocuments,
                subjectTargets,
                AnalysisMode.FAST);
    }

    /** An empty class filter means "every enabled source" — see the command schema. */
    public boolean acceptsAllClasses() {
        return sourceClasses.isEmpty();
    }

    /**
     * "Everything published in the window", the shape a scheduled incremental crawl uses.
     *
     * <p>Connectors whose API demands a search term substitute their configured default query; the
     * ones that can filter by date alone simply drop the term.
     */
    public boolean isWildcard() {
        return "*".equals(query);
    }

    /**
     * Чем спрашивать у внешнего источника.
     *
     * <p>Источники — англоязычные каталоги, и русскую формулировку они понимают как набор
     * незнакомых слов: arXiv на ней ломался, Crossref и OpenAlex отвечали пятьюстами документов ни
     * о чём. Предметные коды направления решают ровно эту задачу — они и есть слова, которыми
     * каталог сам себя размечает.
     *
     * <p>Коды применяются, только когда сам запрос заведомо не на языке источника: запрос,
     * написанный по-английски, отдаётся как есть — аналитик мог спросить точнее, чем умеет словарь,
     * и подменять его формулировку значило бы отвечать не на его вопрос.
     */
    public String upstreamQuery() {
        return String.join(" ", upstreamTerms());
    }

    /**
     * То же самое списком — для источников, которые ищут фразу целиком.
     *
     * <p>Разница не косметическая. Crossref принимает мешок слов и ранжирует по совпадению; arXiv
     * ищет `all:"…"` как одну фразу, а GitHub и OpenAlex соединяют слова через И. Склеенные в строку
     * цели дают там почти ничего: GitHub — ноль репозиториев, OpenAlex — 30 работ вместо пяти
     * миллионов (замер 2026-09-18). Источник при этом отрабатывал без ошибки.
     */
    public List<String> upstreamTerms() {
        if (isWildcard() || subjectTargets.isEmpty() || mayMatchCorpusText()) {
            return List.of(query);
        }
        return subjectTargets;
    }

    /** Есть ли в запросе хоть одно слово на латинице — то есть на языке источников. */
    private boolean mayMatchCorpusText() {
        return TERM_SPLIT
                .splitAsStream(normalizedQuery.toLowerCase(Locale.ROOT))
                .anyMatch(CollectionRequest::mayMatchCorpusText);
    }

    public boolean accepts(SourceClass candidate) {
        return sourceClasses.isEmpty() || sourceClasses.contains(candidate);
    }

    public boolean acceptsAnyOf(Set<SourceClass> candidates) {
        if (sourceClasses.isEmpty()) {
            return true;
        }
        for (SourceClass candidate : candidates) {
            if (sourceClasses.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    public boolean withinWindow(LocalDate date) {
        return date != null && !date.isBefore(windowFrom) && !date.isAfter(windowTo);
    }

    /** Lower-cased alphanumeric terms of the normalised query, used for local matching. */
    /**
     * Чем искать это направление при локальном сопоставлении.
     *
     * <p>Слова самого запроса — и предметные коды направления, если их прислали. Второе решает
     * задачу, которую первое решить не может: корпус размечен английскими кодами источников
     * (`cs.LG`, `artificial intelligence`), а аналитик набирает направление по-русски, и стем
     * «интеллект» не совпадает со стемом «intelligence» ни при каком алгоритме. Без кодов русский
     * запрос не находил ни одного документа — анализ заканчивался отказом раньше, чем начинался.
     *
     * <p>Коды добавляются к словам запроса, а не заменяют их: запрос уже на языке корпуса обязан
     * работать как работал, даже если словарь про такое направление ничего не знает.
     */
    public List<String> terms() {
        var terms = new java.util.LinkedHashSet<String>();
        TERM_SPLIT
                .splitAsStream(normalizedQuery.toLowerCase(Locale.ROOT))
                .filter(term -> !term.isBlank())
                .forEach(terms::add);
        subjectTargets.stream().map(target -> target.toLowerCase(Locale.ROOT)).forEach(terms::add);
        if (subjectTargets.isEmpty() && terms.stream().noneMatch(CollectionRequest::mayMatchCorpusText)) {
            // Направление не нашлось в словаре, и ни одно слово запроса не может совпасть с текстом
            // корпуса: он размечен латиницей, запрос — нет. Сузить выдачу такими словами значит
            // гарантированно получить пустой корпус и ответить «не найдено ни одного документа».
            //
            // Продукт отвечает на этот случай иначе и намеренно: отчётом с оговоркой «направление
            // не распознано» — «мы не поняли вопрос» и «данных нет» это разные ответы, и путать их
            // нельзя. Пустой перечень слов означает «всё, что попало в окно», как у планового
            // обхода; объём при этом ограничен бюджетом документов, как и везде.
            return List.of();
        }
        return List.copyOf(terms);
    }

    /**
     * Может ли слово вообще совпасть с текстом корпуса.
     *
     * <p>Проверка грубая и такой задумана: латинские буквы и цифры — то, чем размечены заголовки,
     * тезисы и предметные коды источников. Слово без единого такого знака не совпадёт ни с чем, и
     * сужать по нему нечего.
     */
    private static boolean mayMatchCorpusText(String term) {
        return term.chars().anyMatch(ch -> (ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9'));
    }
}
