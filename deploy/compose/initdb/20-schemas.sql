-- =============================================================================
--  20 — схемы: по одной на сервис (ADR-0006)
-- =============================================================================
--  Владелец схемы = роль сервиса. Это нужно для Flyway: миграции создают
--  таблицы, индексы и партиции, а значит роль должна иметь CREATE в своей схеме.
--
--  Таблицы здесь НЕ создаются. Единственный источник истины по DDL таблиц —
--  версионированные миграции Flyway/Alembic внутри каждого сервиса (NFR-M7).
--  Дублировать их здесь означало бы завести второй источник истины, который
--  разойдётся с первым в течение недели.
-- =============================================================================

\echo '[20-schemas] ingestion, trends, analytics'

SELECT format('CREATE SCHEMA IF NOT EXISTS %I AUTHORIZATION %I', 'ingestion', :'ingestion_user') \gexec
SELECT format('CREATE SCHEMA IF NOT EXISTS %I AUTHORIZATION %I', 'trends',    :'trends_user')    \gexec
SELECT format('CREATE SCHEMA IF NOT EXISTS %I AUTHORIZATION %I', 'analytics', :'analytics_user') \gexec

-- Если схема уже существовала (например, была создана вручную) — выравниваем
-- владельца, иначе Flyway упадёт на первой же миграции с permission denied.
SELECT format('ALTER SCHEMA %I OWNER TO %I', 'ingestion', :'ingestion_user') \gexec
SELECT format('ALTER SCHEMA %I OWNER TO %I', 'trends',    :'trends_user')    \gexec
SELECT format('ALTER SCHEMA %I OWNER TO %I', 'analytics', :'analytics_user') \gexec

COMMENT ON SCHEMA ingestion IS 'ingestion-service: источники, прогоны, документы, outbox';
COMMENT ON SCHEMA trends    IS 'trends-service: запросы на исследование, отчёты, методология, outbox';
COMMENT ON SCHEMA analytics IS 'analytics-service: снапшоты корпуса, эмбеддинги, словарь терминов, задания';
