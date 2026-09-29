-- =============================================================================
--  30 — гранты: каждая роль видит ТОЛЬКО свою схему (NFR-D1, ADR-0006)
-- =============================================================================
--  «База данных на сервис» в прототипе реализована схемами. Главное свойство
--  этого паттерна — отсутствие скрытой связности через данные — держится ровно
--  на этом файле. Если отсюда что-то выпадет, изоляция превратится в соглашение,
--  а соглашения нарушают.
--
--  Проверка: архитектурный тест в CI ищет обращения к чужим схемам в SQL/JPA,
--  а этот файл делает такие обращения невозможными на уровне СУБД.
-- =============================================================================

\echo '[30-grants] locking down cross-schema access'

-- ─────────────────────── 1. База: подключение только своим ───────────────────────
-- PUBLIC по умолчанию имеет CONNECT к любой базе — отзываем и раздаём поимённо.
SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', current_database()) \gexec

SELECT format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), :'ingestion_user') \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), :'trends_user')    \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO %I', current_database(), :'analytics_user') \gexec

-- Временные таблицы не нужны ни одному сервису; TEMP — это ещё и вектор
-- обхода search_path (подмена таблицы одноимённой временной).
SELECT format('REVOKE TEMPORARY ON DATABASE %I FROM PUBLIC', current_database()) \gexec

-- ─────────────────────── 2. Схема public: только читать типы ───────────────────────
-- В public живёт расширение vector. Роли обязаны видеть его тип,
-- но создавать там ничего не должны.
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

SELECT format('GRANT USAGE ON SCHEMA public TO %I', :'ingestion_user') \gexec
SELECT format('GRANT USAGE ON SCHEMA public TO %I', :'trends_user')    \gexec
SELECT format('GRANT USAGE ON SCHEMA public TO %I', :'analytics_user') \gexec

-- ─────────────────────── 3. Своя схема: полный доступ ───────────────────────
-- CREATE нужен Flyway/Alembic: миграции создают таблицы, индексы, партиции (NFR-M7).
SELECT format('GRANT USAGE, CREATE ON SCHEMA %I TO %I', 'ingestion', :'ingestion_user') \gexec
SELECT format('GRANT USAGE, CREATE ON SCHEMA %I TO %I', 'trends',    :'trends_user')    \gexec
SELECT format('GRANT USAGE, CREATE ON SCHEMA %I TO %I', 'analytics', :'analytics_user') \gexec

-- Права на уже существующие объекты (важно при повторном прогоне init после миграций).
SELECT format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA %I TO %I',  'ingestion', :'ingestion_user') \gexec
SELECT format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA %I TO %I',  'trends',    :'trends_user')    \gexec
SELECT format('GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA %I TO %I',  'analytics', :'analytics_user') \gexec

SELECT format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA %I TO %I', 'ingestion', :'ingestion_user') \gexec
SELECT format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA %I TO %I', 'trends',    :'trends_user')    \gexec
SELECT format('GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA %I TO %I', 'analytics', :'analytics_user') \gexec

-- ─────────────────────── 4. Умолчания для будущих объектов ───────────────────────
-- Таблицы, которые Flyway создаст завтра, не должны быть доступны PUBLIC.
-- ALTER DEFAULT PRIVILEGES действует только на объекты, создаваемые указанной
-- ролью, поэтому FOR ROLE — обязательно, а не «для красоты».
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA %I REVOKE ALL ON TABLES FROM PUBLIC',    :'ingestion_user', 'ingestion') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA %I REVOKE ALL ON TABLES FROM PUBLIC',    :'trends_user',    'trends')    \gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA %I REVOKE ALL ON TABLES FROM PUBLIC',    :'analytics_user', 'analytics') \gexec

SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA %I REVOKE ALL ON SEQUENCES FROM PUBLIC', :'ingestion_user', 'ingestion') \gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA %I REVOKE ALL ON SEQUENCES FROM PUBLIC', :'trends_user',    'trends')    \gexec
SELECT format('ALTER DEFAULT PRIVILEGES FOR ROLE %I IN SCHEMA %I REVOKE ALL ON SEQUENCES FROM PUBLIC', :'analytics_user', 'analytics') \gexec

-- ─────────────────────── 5. Кросс-схемный доступ: отозвать всё ───────────────────────
-- Генерируем REVOKE для каждой пары (схема, чужая роль). Явный REVOKE нужен,
-- даже если гранта никто не выдавал: он делает намерение проверяемым, а если
-- кто-то выдал доступ вручную «на пять минут для отладки» — следующий `make up`
-- его снимет.
WITH mapping(schema_name, owner_role) AS (
    VALUES ('ingestion', :'ingestion_user'),
           ('trends',    :'trends_user'),
           ('analytics', :'analytics_user')
)
SELECT format('REVOKE ALL PRIVILEGES ON SCHEMA %I FROM %I', m.schema_name, o.owner_role)
FROM mapping m
CROSS JOIN mapping o
WHERE m.owner_role <> o.owner_role
\gexec

WITH mapping(schema_name, owner_role) AS (
    VALUES ('ingestion', :'ingestion_user'),
           ('trends',    :'trends_user'),
           ('analytics', :'analytics_user')
)
SELECT format('REVOKE ALL PRIVILEGES ON ALL TABLES IN SCHEMA %I FROM %I', m.schema_name, o.owner_role)
FROM mapping m
CROSS JOIN mapping o
WHERE m.owner_role <> o.owner_role
\gexec

WITH mapping(schema_name, owner_role) AS (
    VALUES ('ingestion', :'ingestion_user'),
           ('trends',    :'trends_user'),
           ('analytics', :'analytics_user')
)
SELECT format('REVOKE ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA %I FROM %I', m.schema_name, o.owner_role)
FROM mapping m
CROSS JOIN mapping o
WHERE m.owner_role <> o.owner_role
\gexec

WITH mapping(schema_name, owner_role) AS (
    VALUES ('ingestion', :'ingestion_user'),
           ('trends',    :'trends_user'),
           ('analytics', :'analytics_user')
)
SELECT format('REVOKE ALL PRIVILEGES ON ALL FUNCTIONS IN SCHEMA %I FROM %I', m.schema_name, o.owner_role)
FROM mapping m
CROSS JOIN mapping o
WHERE m.owner_role <> o.owner_role
\gexec

-- PUBLIC не должен видеть ни одну доменную схему.
REVOKE ALL PRIVILEGES ON SCHEMA ingestion, trends, analytics FROM PUBLIC;

-- ─────────────────────── 6. Самопроверка ───────────────────────
-- Init обязан падать, если изоляция не установилась: молча работающая
-- «почти изоляция» хуже её отсутствия, потому что в неё верят.
DO $$
DECLARE
    leaked record;
    problems int := 0;
BEGIN
    FOR leaked IN
        SELECT n.nspname AS schema_name, r.rolname AS role_name
        FROM pg_namespace n
        CROSS JOIN pg_roles r
        WHERE n.nspname IN ('ingestion', 'trends', 'analytics')
          AND r.rolcanlogin
          AND NOT r.rolsuper
          AND r.rolname <> pg_get_userbyid(n.nspowner)
          AND has_schema_privilege(r.rolname, n.nspname, 'USAGE')
    LOOP
        RAISE WARNING '[30-grants] НАРУШЕНИЕ ИЗОЛЯЦИИ: роль % имеет USAGE на чужую схему %',
                      leaked.role_name, leaked.schema_name;
        problems := problems + 1;
    END LOOP;

    IF problems > 0 THEN
        RAISE EXCEPTION '[30-grants] обнаружено % кросс-схемных грантов — изоляция NFR-D1 нарушена', problems;
    END IF;
    RAISE NOTICE '[30-grants] изоляция схем проверена: кросс-схемных грантов нет';
END
$$;
