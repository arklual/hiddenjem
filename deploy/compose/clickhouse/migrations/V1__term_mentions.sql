-- =============================================================================
--  ClickHouse — факты упоминаний терминов (ADR-0007)
-- =============================================================================
--  DDL перенесён из docs/01-analysis/04-data-model.md §4 без изменений, кроме
--  одного: добавлено `IF NOT EXISTS`. Контейнер clickhouse-init выполняется при
--  каждом `make up`, и без этого второй запуск падал бы на «table already exists».
--  Любое другое расхождение с моделью данных — ошибка: модель является
--  источником истины, а этот файл — её исполняемым отражением.
-- =============================================================================

CREATE TABLE IF NOT EXISTS horizon.term_mentions (
    term_id          UInt64,
    term_normalized  LowCardinality(String),
    document_id      UUID,
    period_start     Date,                      -- начало периода (год/квартал)
    published_on     Date,
    source_id        LowCardinality(String),
    source_class     LowCardinality(String),
    organization     String,
    organization_type LowCardinality(String),
    country          LowCardinality(FixedString(2)),
    venue            String,
    citation_count   UInt32,
    occurrences      UInt16,
    snapshot_id      UUID,
    ingested_at      DateTime64(3)
) ENGINE = ReplacingMergeTree(ingested_at)
PARTITION BY toYYYYMM(period_start)
ORDER BY (snapshot_id, term_id, period_start, document_id)
SETTINGS index_granularity = 8192;

-- Хранение 24 месяца (§6 модели данных). TTL намеренно НЕ включён здесь:
-- политика удаления данных — производственное решение, которое должно
-- приниматься явно и отдельно, а не приезжать вместе с локальным стеком.
-- Включение в проде:
--   ALTER TABLE horizon.term_mentions
--     MODIFY TTL period_start + INTERVAL 24 MONTH DELETE;
