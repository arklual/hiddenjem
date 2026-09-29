#!/usr/bin/env python3
"""Проверка проверки миграций: `tools/check-migrations.py`.

Разбор SQL держится на лексере и полудюжине регулярок, и каждая правка в нём — шанс сломать
молча: проверка продолжит отвечать «замечаний нет» на всех настоящих миграциях, потому что дефектов
в них нет. Именно так первая версия правила M3 прожила до ревью — определение колонки поглощало
`DEFAULT` соседней, и на двадцати настоящих файлах это было незаметно.

Случаи ниже — не выдумка: каждый назван в ревью как ложное срабатывание или пропуск.
Запуск: `python3 tools/test_check_migrations.py`.
"""

from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

_spec = importlib.util.spec_from_file_location(
    "check_migrations", Path(__file__).with_name("check-migrations.py")
)
assert _spec and _spec.loader
check_migrations = importlib.util.module_from_spec(_spec)
sys.modules["check_migrations"] = check_migrations
_spec.loader.exec_module(check_migrations)

#: (описание, SQL, в транзакции?, ожидаемые правила)
CASES: list[tuple[str, str, bool, list[str]]] = [
    (
        "ограничение «одно из двух заполнено» не делает соседнюю колонку дефектной",
        "ALTER TABLE t\n ADD COLUMN a int,\n ADD CONSTRAINT ab CHECK (a IS NOT NULL OR b IS NOT NULL);",
        True,
        [],
    ),
    (
        "генерируемой колонке DEFAULT запрещён синтаксически — требовать его нельзя",
        "ALTER TABLE t ADD COLUMN slug text GENERATED ALWAYS AS (lower(name)) STORED NOT NULL;",
        True,
        [],
    ),
    (
        "CONCURRENTLY внутри строкового литерала — не команда",
        "COMMENT ON INDEX ix IS 'построен через CREATE INDEX CONCURRENTLY';",
        True,
        [],
    ),
    (
        "«--» внутри литерала не съедает остаток строки вместе с DEFAULT",
        "ALTER TABLE t ADD COLUMN sep text NOT NULL CONSTRAINT c CHECK (sep <> '--') DEFAULT '/';",
        True,
        [],
    ),
    (
        "SET NOT NULL на существующей колонке — тот же дефект, что M3, только злее",
        "ALTER TABLE t ALTER COLUMN a SET NOT NULL;",
        True,
        ["M4"],
    ),
    (
        "слово COLUMN необязательно: Postgres принимает ADD без него",
        "ALTER TABLE t ADD owner_id uuid NOT NULL;",
        True,
        ["M3"],
    ),
    (
        "квотированный идентификатор — тоже имя колонки",
        'ALTER TABLE t ADD COLUMN "order" int NOT NULL;',
        True,
        ["M3"],
    ),
    (
        "DEFAULT внутри литерала не оправдывает колонку",
        "ALTER TABLE t ADD COLUMN mode text NOT NULL CHECK (mode <> 'DEFAULT');",
        True,
        ["M3"],
    ),
    (
        "NOT и NULL, разнесённые переносом строки, — то же объявление",
        "ALTER TABLE t ADD COLUMN a int NOT\n    NULL;",
        True,
        ["M3"],
    ),
    (
        "запрет транзакции касается не только CREATE INDEX",
        "DROP INDEX CONCURRENTLY ix_a;",
        True,
        ["M5"],
    ),
    (
        "«/*» внутри литерала не открывает комментарий и не съедает следующий SQL",
        "INSERT INTO cfg(p) VALUES ('/*');\n"
        "ALTER TABLE t ADD COLUMN a int NOT NULL;\n"
        "INSERT INTO cfg(p) VALUES ('*/');",
        True,
        ["M3"],
    ),
    (
        "две колонки в одном выражении: DEFAULT второй не оправдывает первую",
        "ALTER TABLE t ADD COLUMN a text NOT NULL, ADD COLUMN b text NOT NULL DEFAULT '';",
        True,
        ["M3"],
    ),
    (
        "блочные комментарии в Postgres вложенные",
        "/* внешний /* внутренний */ ещё комментарий */\nALTER TABLE t ADD COLUMN a int NOT NULL;",
        True,
        ["M3"],
    ),
    (
        "вне транзакции CONCURRENTLY законен и остаётся единственным безопасным способом",
        "CREATE INDEX CONCURRENTLY ix ON t (a);",
        False,
        [],
    ),
    (
        "исправная колонка замечаний не вызывает",
        "ALTER TABLE t ADD COLUMN a boolean NOT NULL DEFAULT true;",
        True,
        [],
    ),
    (
        "тело функции в долларовых кавычках не разбирается как команды",
        "CREATE FUNCTION f() RETURNS void AS $$\n"
        "BEGIN\n"
        "  EXECUTE 'ALTER TABLE t ADD COLUMN a int NOT NULL';\n"
        "END;\n$$ LANGUAGE plpgsql;",
        True,
        [],
    ),
]


def main() -> int:
    failures: list[str] = []
    for description, sql, transactional, expected in CASES:
        actual = sorted(
            problem.rule
            for problem in check_migrations.check_sql(Path("V1__probe.sql"), sql, transactional)
        )
        mark = "✓" if actual == sorted(expected) else "✗"
        print(f"  {mark} {description}")
        if actual != sorted(expected):
            failures.append(f"{description}: ожидалось {sorted(expected)}, получено {actual}")

    if failures:
        print("\nразбор SQL разошёлся с ожиданиями:", file=sys.stderr)
        for failure in failures:
            print(f"  ✗ {failure}", file=sys.stderr)
        return 1

    print(f"\nразбор проверен на {len(CASES)} случаях")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
