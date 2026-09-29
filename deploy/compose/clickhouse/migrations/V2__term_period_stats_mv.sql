-- =============================================================================
--  ClickHouse — материализованное представление рядов df/tf по периодам
-- =============================================================================
--  DDL перенесён из docs/01-analysis/04-data-model.md §4 без изменений, кроме
--  добавленного `IF NOT EXISTS` (идемпотентность повторного запуска init).
--
--  ЗАМЕЧАНИЕ ДЛЯ ВЛАДЕЛЬЦА МОДЕЛИ ДАННЫХ (не меняю DDL в одностороннем порядке):
--  `uniqExact(document_id)` и `uniqExact(organization)` в SummingMergeTree дают
--  корректный результат только пока каждая вставка попадает в отдельную строку
--  и слияний по ключу не происходит: SummingMergeTree складывает числа, а не
--  объединяет множества, поэтому после мержа двух частей уникальные счётчики
--  сложатся и df окажется завышенным при повторной записи того же снапшота.
--  Канонические варианты: AggregatingMergeTree + uniqExactState/uniqExactMerge,
--  либо гарантия «один снапшот пишется ровно один раз» на стороне писателя
--  (сейчас она есть: снапшот идемпотентен по content_hash, §4 модели данных).
--  Вынесено в бэклог как вопрос к модели, а не как самовольное изменение.
-- =============================================================================

CREATE MATERIALIZED VIEW IF NOT EXISTS horizon.term_period_stats_mv
ENGINE = SummingMergeTree
PARTITION BY toYYYYMM(period_start)
ORDER BY (snapshot_id, term_id, period_start, source_class)
AS SELECT snapshot_id, term_id, period_start, source_class,
          uniqExact(document_id) AS df, sum(occurrences) AS tf,
          uniqExact(organization) AS orgs, uniqExact(venue) AS venues
   FROM horizon.term_mentions
   GROUP BY snapshot_id, term_id, period_start, source_class;
