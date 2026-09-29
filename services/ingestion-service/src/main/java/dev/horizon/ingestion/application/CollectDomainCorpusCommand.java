package dev.horizon.ingestion.application;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import dev.horizon.ingestion.domain.document.SourceClass;
import dev.horizon.ingestion.domain.port.AnalysisMode;
import dev.horizon.ingestion.domain.port.CollectionRequest;
import dev.horizon.platform.common.util.Guards;

/**
 * Application-level command mirroring {@code contracts/schemas/collect-domain-corpus.command.json}.
 *
 * <p>Validation happens here, at the edge of the application: an invalid command is a contract
 * violation and must be rejected before it can start a collection.
 */
public record CollectDomainCorpusCommand(
        UUID researchRequestId,
        int attempt,
        String query,
        String normalizedQuery,
        String queryLanguage,
        LocalDate windowFrom,
        LocalDate windowTo,
        Set<SourceClass> sourceClasses,
        int maxDocuments,
        /**
         * Предметные коды корпуса для этого направления, добытые перекрёстным словарём движка.
         *
         * <p>Поле необязательное — команда без него та же команда, и сбор тогда идёт по словам
         * запроса. Обязательным его делать нельзя: два сервиса выкатываются по отдельности, и
         * жёсткое требование означало бы, что во время выката ни один запрос не соберётся.
         */
        List<String> subjectTargets,
        /**
         * Режим анализа: от него зависят бюджеты источников, прежде всего глубокого исследования.
         *
         * <p>Необязательный по той же причине, что и предметные коды: отсутствие — быстрый режим,
         * как было до появления поля.
         */
        AnalysisMode mode) {

    public static final int MIN_MAX_DOCUMENTS = 100;
    public static final int MAX_MAX_DOCUMENTS = 100_000;

    public CollectDomainCorpusCommand {
        Guards.requireNonNull(researchRequestId, "researchRequestId");
        Guards.requireArgument(attempt >= 1, "attempt must be at least 1");
        query = Guards.requireLength(query, "query", 1, 200);
        normalizedQuery = Guards.requireLength(normalizedQuery, "normalizedQuery", 1, 200);
        Guards.requireNonNull(windowFrom, "windowFrom");
        Guards.requireNonNull(windowTo, "windowTo");
        Guards.requireArgument(!windowFrom.isAfter(windowTo), "windowFrom must not be after windowTo");
        sourceClasses = sourceClasses == null ? Set.of() : Set.copyOf(sourceClasses);
        maxDocuments = maxDocuments <= 0
                ? CollectionRequest.DEFAULT_MAX_DOCUMENTS
                : Guards.requireRange(maxDocuments, "maxDocuments", MIN_MAX_DOCUMENTS, MAX_MAX_DOCUMENTS);
        subjectTargets = subjectTargets == null ? List.of() : List.copyOf(subjectTargets);
        mode = mode == null ? AnalysisMode.FAST : mode;
    }

    /** Команда быстрого режима — вида, который был до появления поля {@code mode}. */
    public CollectDomainCorpusCommand(
            UUID researchRequestId,
            int attempt,
            String query,
            String normalizedQuery,
            String queryLanguage,
            LocalDate windowFrom,
            LocalDate windowTo,
            Set<SourceClass> sourceClasses,
            int maxDocuments,
            List<String> subjectTargets) {
        this(
                researchRequestId,
                attempt,
                query,
                normalizedQuery,
                queryLanguage,
                windowFrom,
                windowTo,
                sourceClasses,
                maxDocuments,
                subjectTargets,
                AnalysisMode.FAST);
    }

    public static CollectDomainCorpusCommand of(
            UUID researchRequestId,
            int attempt,
            String query,
            String normalizedQuery,
            String queryLanguage,
            LocalDate windowFrom,
            LocalDate windowTo,
            List<String> sourceClasses,
            Integer maxDocuments,
            List<String> subjectTargets,
            String mode) {
        Set<SourceClass> classes = new LinkedHashSet<>();
        if (sourceClasses != null) {
            for (String value : sourceClasses) {
                SourceClass.parse(value).ifPresentOrElse(classes::add, () -> {
                    throw new IllegalArgumentException("Unknown source class: " + value);
                });
            }
        }
        return new CollectDomainCorpusCommand(
                researchRequestId,
                attempt,
                query,
                normalizedQuery,
                queryLanguage,
                windowFrom,
                windowTo,
                classes,
                maxDocuments == null ? CollectionRequest.DEFAULT_MAX_DOCUMENTS : maxDocuments,
                subjectTargets,
                AnalysisMode.parse(mode));
    }

    /** The idempotency key of this unit of work (FR-05.6): one corpus per request attempt. */
    public String idempotencyKey() {
        return "collect:" + researchRequestId + ":" + attempt;
    }

    /**
     * Запрос к источнику по одной узкой формулировке расширения.
     *
     * <p>Формулировка уже на языке, которым спрашивают источник, поэтому уходит как есть: коды направления здесь не
     * нужны — узкий запрос и есть то, что спрашивают, а окно и классы источников — те же, что у
     * запроса направления.
     */
    public CollectionRequest toExpansionRequest(String phrase, String language, int documentBudget) {
        return new CollectionRequest(
                researchRequestId,
                phrase,
                phrase,
                language == null ? "en" : language,
                windowFrom,
                windowTo,
                sourceClasses,
                Math.max(documentBudget, 1),
                List.of(),
                mode);
    }

    public CollectionRequest toCollectionRequest(int documentBudget) {
        return new CollectionRequest(
                researchRequestId,
                query,
                normalizedQuery,
                queryLanguage,
                windowFrom,
                windowTo,
                sourceClasses,
                Math.max(documentBudget, 1),
                subjectTargets,
                mode);
    }
}
