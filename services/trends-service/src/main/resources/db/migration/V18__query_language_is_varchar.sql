-- `char(2)` против `String`: сервис не стартует по той же причине, что и в V17.
--
-- Драйвер Postgres отдаёт `char(n)` как `Types.CHAR`, а поле `String` без уточнений Hibernate
-- ожидает как VARCHAR, — и `ddl-auto: validate` отказывается поднимать контекст: «found [bpchar],
-- but expecting [varchar(2)]». Это второе расхождение того же рода; чтобы третьего не искать
-- запуском, добавлена проверка `tools/check-schema-mapping.py` — она нашла ровно четыре и на
-- этом закончила.
--
-- `char` здесь ничего не давал сверх длины: код языка ISO 639-1 и так ровно двухбуквенный, а
-- дополнять пробелами до длины (единственное, чем `char` отличается) для кода языка бессмысленно.

ALTER TABLE research_requests
    ALTER COLUMN query_language TYPE varchar(2);

ALTER TABLE saved_domains
    ALTER COLUMN query_language TYPE varchar(2);
