#!/usr/bin/env python3
"""Совпадает ли тип колонки в миграции с типом поля сущности.

Зачем
─────
`ddl-auto: validate` сверяет каждую отображённую колонку с полем сущности и при первом же
расхождении отказывается поднимать контекст: «wrong column type encountered in column [...]
found [bpchar], but expecting [varchar(2)]». Отказ жёсткий, наступает только против настоящей
базы и сообщает ровно об одном расхождении за запуск — остальные видны лишь после починки
предыдущего. При развёртывании это стоило нескольких кругов по пять минут каждый.

Расхождение при этом целиком выражено в репозитории: слева файл миграции, справа файл сущности.
Значит, его можно назвать до запуска — чем проверка и занимается.

Что считается расхождением
──────────────────────────
Для каждого поля с `@Column(name = ...)` берётся тип Java, для каждой колонки — тип SQL после
применения всех миграций по порядку (CREATE TABLE, затем ALTER COLUMN TYPE / ADD / DROP).
Пара сверяется по таблице соответствий ниже — той самой, по которой сверяет Hibernate.

Чего проверка не знает
──────────────────────
Поля с `@Convert`, `@JdbcTypeCode`, `@Enumerated` и составные типы пропускаются: их отображение
задаётся не типом поля. Пропуски **считаются и печатаются** — молчаливое сокращение охвата
выглядело бы как «проверено всё», а проверено не всё.

Выход: 0 — расхождений нет; 1 — есть.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent

#: Тип Java → допустимые типы Postgres. Порядок внутри значения роли не играет.
#:
#: Соответствие взято по правилу Hibernate 6: сравниваются коды JDBC, а не имена. Поэтому
#: `text` для строки допустим (драйвер Postgres отдаёт его как VARCHAR), а `char(n)` — нет:
#: он приходит как CHAR и валидатор считает это расхождением.
EXPECTED = {
    "String": {"varchar", "text", "citext"},
    "UUID": {"uuid"},
    "boolean": {"bool", "boolean"},
    "Boolean": {"bool", "boolean"},
    "short": {"int2", "smallint"},
    "Short": {"int2", "smallint"},
    "int": {"int4", "integer", "int"},
    "Integer": {"int4", "integer", "int"},
    "long": {"int8", "bigint"},
    "Long": {"int8", "bigint"},
    "float": {"float4", "real"},
    "Float": {"float4", "real"},
    "double": {"float8", "double precision"},
    "Double": {"float8", "double precision"},
    "BigDecimal": {"numeric", "decimal"},
    "Instant": {"timestamptz", "timestamp with time zone"},
    "OffsetDateTime": {"timestamptz", "timestamp with time zone"},
    "LocalDateTime": {"timestamp", "timestamp without time zone"},
    "LocalDate": {"date"},
    "byte[]": {"bytea"},
}

#: Аннотации, при которых тип поля не определяет тип колонки.
OPAQUE = ("@Convert", "@JdbcTypeCode", "@Enumerated", "@Type", "@Lob")

CREATE_TABLE = re.compile(r"CREATE TABLE (?:IF NOT EXISTS )?([a-z_][a-z0-9_.]*)\s*\(", re.I)
ALTER_TABLE = re.compile(r"ALTER TABLE (?:IF EXISTS )?([a-z_][a-z0-9_.]*)(.*?);", re.I | re.S)
#: Тип занимает от одного до четырёх слов, за ним могут идти длина, `[]` и любые ограничения.
#: Многословные типы перечислены явно и стоят первыми: иначе `double precision` обрезалось бы до
#: `double`, а `timestamp with time zone` — до `timestamp`, то есть проверка сравнивала бы не то.
SQL_TYPE = (
    r"(?:[a-z_][a-z0-9_]*\.)?"
    r"(double precision|character varying|bit varying|timestamp with time zone|"
    r"timestamp without time zone|time with time zone|time without time zone|[a-z_][a-z0-9_]*)"
    r"(?:\s*\([^)]*\))?(?:\s*\[\])?"
)
COLUMN_LINE = re.compile(rf"^\s*([a-z_][a-z0-9_]*)\s+{SQL_TYPE}", re.I)
NOT_A_COLUMN = re.compile(
    r"^\s*(PRIMARY|FOREIGN|UNIQUE|CHECK|CONSTRAINT|EXCLUDE|LIKE|--|\)|$)", re.I
)


def base_type(sql: str) -> str:
    """Тип без длины, схемы и пробельного шума: `public.citext` → `citext`."""
    return re.sub(r"\s+", " ", sql.strip().lower())


def columns_of(service: Path) -> dict[str, dict[str, str]]:
    """Таблица → колонка → тип SQL после применения всех миграций сервиса по порядку."""
    migrations = service / "src" / "main" / "resources" / "db" / "migration"
    if not migrations.is_dir():
        return {}

    def version(path: Path) -> int:
        match = re.match(r"V(\d+)__", path.name)
        return int(match.group(1)) if match else 0

    tables: dict[str, dict[str, str]] = {}
    for path in sorted(migrations.glob("V*.sql"), key=version):
        text = path.read_text(encoding="utf-8")

        for match in CREATE_TABLE.finditer(text):
            name = match.group(1).split(".")[-1].lower()
            body, depth, index = [], 1, match.end()
            while index < len(text) and depth:
                char = text[index]
                depth += (char == "(") - (char == ")")
                if depth:
                    body.append(char)
                index += 1
            columns: dict[str, str] = {}
            for line in "".join(body).splitlines():
                if NOT_A_COLUMN.match(line):
                    continue
                found = COLUMN_LINE.match(line)
                if found:
                    columns[found.group(1).lower()] = base_type(found.group(2))
            tables.setdefault(name, {}).update(columns)

        for match in ALTER_TABLE.finditer(text):
            name = match.group(1).split(".")[-1].lower()
            for column, sql_type in re.findall(
                rf"ALTER COLUMN\s+([a-z_][a-z0-9_]*)\s+(?:SET DATA )?TYPE\s+{SQL_TYPE}",
                match.group(2),
                re.I,
            ):
                tables.setdefault(name, {})[column.lower()] = base_type(sql_type)
            for column, sql_type in re.findall(
                rf"ADD COLUMN\s+(?:IF NOT EXISTS\s+)?([a-z_][a-z0-9_]*)\s+{SQL_TYPE}",
                match.group(2),
                re.I,
            ):
                tables.setdefault(name, {})[column.lower()] = base_type(sql_type)
            for column in re.findall(
                r"DROP COLUMN\s+(?:IF EXISTS\s+)?([a-z_][a-z0-9_]*)", match.group(2), re.I
            ):
                tables.get(name, {}).pop(column.lower(), None)
    return tables


def fields_of(service: Path) -> list[tuple[Path, str, str, str, bool]]:
    """Поля сущностей: файл, таблица, колонка, тип Java и признак «отображение непрозрачно»."""
    found: list[tuple[Path, str, str, str, bool]] = []
    for path in service.rglob("*.java"):
        text = path.read_text(encoding="utf-8")
        if "@Entity" not in text:
            continue
        table = re.search(r"@Table\s*\(\s*name\s*=\s*\"([a-z_][a-z0-9_]*)\"", text)
        if not table:
            continue
        for match in re.finditer(
            r"@Column\s*\([^)]*name\s*=\s*\"([a-z_][a-z0-9_]*)\"[^)]*\)"
            r"((?:\s*@\w+(?:\([^)]*\))?)*)"
            r"\s*private\s+(?:final\s+)?([\w.<>\[\]]+)\s+\w+\s*;",
            text,
        ):
            column, between, java = match.group(1), match.group(2), match.group(3)
            head = text[max(0, match.start() - 200) : match.start()]
            opaque = any(marker in between or marker in head for marker in OPAQUE)
            found.append((path, table.group(1).lower(), column.lower(), java.split("<")[0], opaque))
    return found


def main() -> int:
    problems: list[str] = []
    skipped = checked = 0

    for service in sorted((ROOT / "services").iterdir()):
        java_root = service / "src" / "main" / "java"
        if not java_root.is_dir():
            continue
        tables = columns_of(service)
        if not tables:
            continue
        for path, table, column, java, opaque in fields_of(java_root):
            sql_type = tables.get(table, {}).get(column)
            if sql_type is None or opaque or java not in EXPECTED:
                skipped += 1
                continue
            checked += 1
            if sql_type not in EXPECTED[java]:
                problems.append(
                    f"      {table}.{column}: миграция даёт {sql_type},"
                    f" поле {java} требует {'|'.join(sorted(EXPECTED[java]))}"
                    f"\n          {path.relative_to(ROOT)}"
                )

    if problems:
        print(f"  ✗ колонок расходится с полями: {len(problems)} из {checked}")
        for problem in problems:
            print(problem)
        return 1
    print(f"  ✓ типы колонок совпадают с полями: проверено {checked}, пропущено {skipped}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
