-- =============================================================================
--  ClickHouse — размер корпуса по периодам и классам источника
-- =============================================================================
--  Знаменатель для DoV/DoD: доля документов с термином считается относительно
--  общего числа документов периода (см. docs/03-methodology).
--
--  DDL перенесён из docs/01-analysis/04-data-model.md §4 без изменений, кроме
--  добавленного `IF NOT EXISTS`.
-- =============================================================================

CREATE TABLE IF NOT EXISTS horizon.corpus_period_stats (
    snapshot_id UUID, period_start Date, source_class LowCardinality(String),
    document_count UInt64
) ENGINE = ReplacingMergeTree ORDER BY (snapshot_id, period_start, source_class);
