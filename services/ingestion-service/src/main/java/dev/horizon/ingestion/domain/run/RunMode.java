package dev.horizon.ingestion.domain.run;

/**
 * Why a run was started.
 *
 * <p>Matches the {@code CHECK} constraint on {@code ingestion_runs.mode} and the OpenAPI
 * {@code IngestionRunView.mode}.
 */
public enum RunMode {
    /** Scheduled crawl continuing from the persisted cursor (UC-16). */
    INCREMENTAL,
    /** Operator-driven historical sweep over an explicit window. */
    BACKFILL,
    /** Collection triggered by a {@code CollectDomainCorpus} command for one research request. */
    ON_DEMAND
}
