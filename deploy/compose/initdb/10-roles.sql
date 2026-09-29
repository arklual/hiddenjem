-- =============================================================================
--  10 — роли приложений: по одной на сервис (ADR-0006, NFR-D1)
-- =============================================================================
--  Пароли приходят переменными psql (-v trends_password=...), которые задаёт
--  postgres-init из окружения. В файле нет ни одного секрета (NFR-S2).
--
--  Почему не DO $$ ... $$: psql НЕ подставляет свои переменные внутрь
--  dollar-quoted строк. Идиома `SELECT format(...) \gexec` — единственный
--  корректный способ собрать DDL с именем роли из переменной.
--  %I квотирует идентификатор, %L — литерал; инъекция через имя невозможна.
-- =============================================================================

\echo '[10-roles] creating per-service login roles'

-- ─────────────────────────── ingestion_app ───────────────────────────
SELECT format('CREATE ROLE %I LOGIN', :'ingestion_user')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'ingestion_user')
\gexec

-- ─────────────────────────── trends_app ───────────────────────────
SELECT format('CREATE ROLE %I LOGIN', :'trends_user')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'trends_user')
\gexec

-- ─────────────────────────── analytics_app ───────────────────────────
SELECT format('CREATE ROLE %I LOGIN', :'analytics_user')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'analytics_user')
\gexec

-- Пароль переустанавливается всегда: источник истины — .env, а не состояние БД.
-- Иначе смена пароля в .env тихо не применялась бы, и сервис падал бы на старте
-- с невнятным «password authentication failed».
SELECT format('ALTER ROLE %I WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS PASSWORD %L', :'ingestion_user', :'ingestion_password') \gexec
SELECT format('ALTER ROLE %I WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS PASSWORD %L', :'trends_user',    :'trends_password')    \gexec
SELECT format('ALTER ROLE %I WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS PASSWORD %L', :'analytics_user', :'analytics_password') \gexec

-- Ограничение числа соединений на роль: один взбесившийся сервис не должен
-- выесть весь пул max_connections и утащить за собой остальные два.
SELECT format('ALTER ROLE %I CONNECTION LIMIT 25', :'ingestion_user') \gexec
SELECT format('ALTER ROLE %I CONNECTION LIMIT 25', :'trends_user')    \gexec
SELECT format('ALTER ROLE %I CONNECTION LIMIT 25', :'analytics_user') \gexec

-- search_path прибит гвоздями: <своя схема>, public (для типа vector).
-- Без этого сервис случайно найдёт таблицу-однофамильца в чужой схеме,
-- если ему когда-нибудь выдадут туда USAGE.
SELECT format('ALTER ROLE %I SET search_path = %I, public', :'ingestion_user', 'ingestion') \gexec
SELECT format('ALTER ROLE %I SET search_path = %I, public', :'trends_user',    'trends')    \gexec
SELECT format('ALTER ROLE %I SET search_path = %I, public', :'analytics_user', 'analytics') \gexec

-- Все временные метки — UTC (NFR-D4). Ставим на уровне роли, чтобы значение
-- не зависело от настроек клиента.
SELECT format('ALTER ROLE %I SET timezone = %L', :'ingestion_user', 'UTC') \gexec
SELECT format('ALTER ROLE %I SET timezone = %L', :'trends_user',    'UTC') \gexec
SELECT format('ALTER ROLE %I SET timezone = %L', :'analytics_user', 'UTC') \gexec

-- Запрос, висящий больше 60 с, — это либо ошибка, либо забытый ANALYZE.
-- Пусть падает сам, а не держит блокировки до бесконечности.
SELECT format('ALTER ROLE %I SET statement_timeout = %L', :'ingestion_user', '120s') \gexec
SELECT format('ALTER ROLE %I SET statement_timeout = %L', :'trends_user',    '60s') \gexec
SELECT format('ALTER ROLE %I SET statement_timeout = %L', :'analytics_user', '300s') \gexec

SELECT format('ALTER ROLE %I SET idle_in_transaction_session_timeout = %L', :'ingestion_user', '30s') \gexec
SELECT format('ALTER ROLE %I SET idle_in_transaction_session_timeout = %L', :'trends_user',    '30s') \gexec
SELECT format('ALTER ROLE %I SET idle_in_transaction_session_timeout = %L', :'analytics_user', '60s') \gexec
