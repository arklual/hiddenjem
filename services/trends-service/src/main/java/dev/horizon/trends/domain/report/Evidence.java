package dev.horizon.trends.domain.report;

import java.time.LocalDate;

import dev.horizon.platform.common.util.Guards;

/**
 * A primary source backing a trend (BR-A5).
 *
 * <p>A trend without at least one of these is never shown (BR-A6) — the product refuses to make
 * untraceable claims.
 */
public record Evidence(
        String sourceId,
        SourceClass sourceClass,
        String externalId,
        String title,
        String authors,
        String organization,
        String organizationCountry,
        LocalDate publishedOn,
        String url,
        String doi,
        Integer citationCount,
        double relevance,
        String snippet,
        /**
         * Язык оригинала, ISO 639-1; {@code null}, если источник его не сообщил.
         *
         * <p>ТЗ требует показывать его у каждого источника, и требование содержательное:
         * русскоязычное резюме зарубежного материала имеет смысл только вместе с указанием, с
         * какого языка оно сделано.
         */
        String language,
        /** Уровень доверенности; выводится движком, здесь только переносится (ADR-0016). */
        Credibility credibility,
        /**
         * Правило, присвоившее уровень, словами.
         *
         * <p>ТЗ допускает показывать «уровень доверенности либо критерии его определения».
         * Показываются оба: ярлык без основания читатель проверить не может, а весь смысл
         * продукта — в проверяемости утверждений.
         */
        String credibilityBasis,
        /**
         * Самостоятельное ли это свидетельство.
         *
         * <p>{@code false} у перепечаток пресс-релизов, соцсетей и личных площадок: десять сайтов
         * с одним релизом — один источник, а не десять, и правило достоверности обязано считать
         * их за один.
         */
        boolean independent) {

    public Evidence {
        Guards.requireText(sourceId, "evidence.sourceId");
        Guards.requireNonNull(sourceClass, "evidence.sourceClass");
        Guards.requireText(title, "evidence.title");
        Guards.requireNonNull(publishedOn, "evidence.publishedOn");
        Guards.requireText(url, "evidence.url");
        Guards.requireRange(relevance, "evidence.relevance", 0.0, 1.0);
        // Умолчание средним, а не высоким и не низким: отсутствие уровня не даёт оснований ни
        // поручиться за источник, ни усомниться в нём.
        credibility = credibility == null ? Credibility.MEDIUM : credibility;
    }

    /**
     * Источник без языка и уровня доверенности.
     *
     * <p>Перегрузка, а не правка каждого места вызова: поля добавочные, и прежняя форма обязана
     * значить то же, что значила. Умолчания здесь — «язык неизвестен» и «средняя доверенность»,
     * то есть ровно то, что можно утверждать, ничего не зная.
     */
    public Evidence(
            String sourceId,
            SourceClass sourceClass,
            String externalId,
            String title,
            String authors,
            String organization,
            String organizationCountry,
            LocalDate publishedOn,
            String url,
            String doi,
            Integer citationCount,
            double relevance,
            String snippet) {
        this(
                sourceId,
                sourceClass,
                externalId,
                title,
                authors,
                organization,
                organizationCountry,
                publishedOn,
                url,
                doi,
                citationCount,
                relevance,
                snippet,
                null,
                Credibility.MEDIUM,
                null,
                true);
    }
}
