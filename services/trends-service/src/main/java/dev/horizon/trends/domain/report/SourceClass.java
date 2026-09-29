package dev.horizon.trends.domain.report;

/** Class of a primary source. Mirrors the enum in the published contracts. */
public enum SourceClass {
    PREPRINT,
    JOURNAL_ARTICLE,
    PATENT,
    CODE_REPOSITORY,
    NEWS,
    ANALYST_REPORT,
    STANDARD
}
