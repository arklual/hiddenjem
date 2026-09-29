#!/usr/bin/env python3
"""Проверка миграций на дефекты, которые иначе обнаружатся только при развёртывании.

Миграции — единственный слой, где ошибка не видна ни компилятору, ни тестам, ни ревью: файл
синтаксически верен, тесты идут на пустой схеме, а сервис не поднимается уже на стенде. Проверки
ниже ловят ровно те дефекты, которые проходят всё остальное.

Почему это заведено сейчас. В репозитории работает больше одного автора одновременно, и номер
версии — общий ресурс без блокировки: две ветки независимо добавляют `V12__…`, обе зелёные, а
Flyway при старте отказывается работать с обеими. Дефект появляется в момент слияния, когда ни один
из авторов на него уже не смотрит.

Проверки:

M1  Номер версии уникален внутри сервиса.
M2  Имя файла соответствует соглашению проекта `V<число>__<описание>.sql`.
M3  `ADD COLUMN ... NOT NULL` без `DEFAULT` — отказ на непустой таблице. Худший вид: в CI схема
    пустая, миграция проходит; на стенде с данными — падает.
M4  `SET NOT NULL` на существующей колонке — то же самое, но злее: полный проход под
    ACCESS EXCLUSIVE и жёсткий отказ, если найдётся хоть один NULL.
M5  `CONCURRENTLY` внутри миграции, выполняемой в транзакции: Postgres такую команду в транзакции
    не принимает. Разрешено при соседнем `.conf` с `executeInTransaction=false`.

Разбор ведётся лексером, а не набором регулярок по сырому тексту. Комментарии в этих файлах
длиннее самого SQL, а строковые литералы содержат и `--`, и `/*`; регулярка по сырому тексту
находила дефекты в пояснениях и теряла их в коде.

Сознательно не проверяются два правила — обоснование в `docs/01-analysis/29-migration-safety-spec.md`.
"""

from __future__ import annotations

import re
import sys
from dataclasses import dataclass
from pathlib import Path

FILENAME = re.compile(r"^V(\d+)__[A-Za-z0-9_]+\.sql$", re.IGNORECASE)

#: Начало определения колонки: `ADD [COLUMN] [IF NOT EXISTS] <имя>`. Слово `COLUMN` необязательно —
#: Postgres его не требует, и `ALTER TABLE t ADD owner_id uuid NOT NULL` тоже роняет стенд.
ADD_COLUMN = re.compile(
    r"\bADD\s+(?:COLUMN\s+)?(?:IF\s+NOT\s+EXISTS\s+)?(\"[^\"]+\"|\w+)",
    re.IGNORECASE,
)
#: Конец определения колонки: следующее `ADD` любого рода. `ADD CONSTRAINT … CHECK (a IS NOT NULL)`
#: обязан обрывать определение, иначе ограничение «одно из двух заполнено» объявляет соседнюю
#: колонку дефектной.
NEXT_CLAUSE = re.compile(r"\bADD\s|\bALTER\s|\bDROP\s|;", re.IGNORECASE)
SET_NOT_NULL = re.compile(
    r"\bALTER\s+(?:COLUMN\s+)?(\"[^\"]+\"|\w+)\s+SET\s+NOT\s+NULL", re.IGNORECASE
)
CONCURRENTLY = re.compile(r"\bCONCURRENTLY\b", re.IGNORECASE)
#: `NOT NULL` объявления колонки. `IS NOT NULL` — это выражение внутри ограничения, а не объявление.
DECLARES_NOT_NULL = re.compile(r"(?<!IS)\s+NOT\s+NULL", re.IGNORECASE)
HAS_DEFAULT = re.compile(r"\bDEFAULT\b", re.IGNORECASE)
#: Генерируемой колонке `DEFAULT` запрещён синтаксически, требовать его бессмысленно.
GENERATED = re.compile(r"\bGENERATED\s+ALWAYS\s+AS\b", re.IGNORECASE)


@dataclass(frozen=True)
class Problem:
    path: Path
    rule: str
    message: str

    def render(self) -> str:
        return f"{self.path}: {self.message} ({self.rule})"


def strip_noise(sql: str) -> str:
    """Заменить комментарии и строковые литералы пробелами, сохранив длину строк.

    Однопроходный лексер, а не регулярки. Регулярка по сырому тексту ошибается в обе стороны:
    находит `CREATE INDEX CONCURRENTLY` внутри пояснительного комментария и теряет настоящий SQL,
    когда литерал содержит `/*`. В этих файлах и того, и другого хватает: комментарии объясняют,
    зачем заведена каждая колонка, а seed-миграции вставляют строки с произвольным содержимым.

    Литералы заменяются пробелами, а не удаляются, чтобы `DEFAULT` внутри `CHECK (x <> 'DEFAULT')`
    не оправдывал колонку и чтобы позиции в тексте не поехали.
    """
    out: list[str] = []
    index = 0
    length = len(sql)
    while index < length:
        char = sql[index]
        rest = sql[index:]

        if rest.startswith("--"):
            end = sql.find("\n", index)
            end = length if end < 0 else end
            out.append(" " * (end - index))
            index = end
        elif rest.startswith("/*"):
            # Блочные комментарии в Postgres вложенные, поэтому считаем глубину, а не ищем первый
            # закрывающий: `/* … /* … */ … */` съел бы половину файла при наивном поиске.
            depth, cursor = 1, index + 2
            while cursor < length and depth:
                if sql.startswith("/*", cursor):
                    depth, cursor = depth + 1, cursor + 2
                elif sql.startswith("*/", cursor):
                    depth, cursor = depth - 1, cursor + 2
                else:
                    cursor += 1
            out.append(" " * (cursor - index))
            index = cursor
        elif char == "'":
            cursor = index + 1
            while cursor < length:
                if sql[cursor] == "'":
                    # Удвоенная кавычка — экранированная, литерал продолжается.
                    if cursor + 1 < length and sql[cursor + 1] == "'":
                        cursor += 2
                        continue
                    cursor += 1
                    break
                cursor += 1
            out.append(" " * (cursor - index))
            index = cursor
        elif char == "$":
            tag = re.match(r"\$\w*\$", rest)
            if tag is None:
                out.append(char)
                index += 1
                continue
            marker = tag.group(0)
            end = sql.find(marker, index + len(marker))
            end = length if end < 0 else end + len(marker)
            out.append(" " * (end - index))
            index = end
        else:
            out.append(char)
            index += 1
    return "".join(out)


def normalize(text: str) -> str:
    """Схлопнуть пробелы: `NOT\\n    NULL` — то же объявление, что `NOT NULL`."""
    return re.sub(r"\s+", " ", text)


def check_sql(path: Path, sql: str, transactional: bool) -> list[Problem]:
    problems: list[Problem] = []
    clean = strip_noise(sql)

    for match in ADD_COLUMN.finditer(clean):
        tail = clean[match.end() :]
        boundary = NEXT_CLAUSE.search(tail)
        definition = normalize(tail[: boundary.start()] if boundary else tail)
        if not DECLARES_NOT_NULL.search(definition) or GENERATED.search(definition):
            continue
        if not HAS_DEFAULT.search(definition):
            problems.append(
                Problem(
                    path,
                    "M3",
                    f"колонка «{match.group(1)}» добавляется как NOT NULL без DEFAULT — миграция "
                    f"пройдёт на пустой схеме в CI и упадёт на таблице с данными",
                )
            )

    for match in SET_NOT_NULL.finditer(normalize(clean)):
        problems.append(
            Problem(
                path,
                "M4",
                f"колонке «{match.group(1)}» выставляется NOT NULL — полный проход под "
                f"ACCESS EXCLUSIVE и отказ, если найдётся хоть один NULL; в CI схема пуста",
            )
        )

    if transactional and CONCURRENTLY.search(clean):
        problems.append(
            Problem(
                path,
                "M5",
                "CONCURRENTLY внутри миграции, выполняемой в транзакции — Postgres такую команду "
                "в транзакции не принимает; разрешите её файлом "
                f"«{path.name}.conf» со строкой executeInTransaction=false",
            )
        )

    return problems


def runs_in_transaction(path: Path) -> bool:
    """Выполняется ли миграция в транзакции.

    Flyway читает соседний `<имя>.conf`; `executeInTransaction=false` — единственный законный
    способ построить индекс без блокировки записи на заполненной таблице. Запрещать его безусловно
    значило бы толкать к обычному `CREATE INDEX`, который держит таблицу всё время построения — на
    старте сервиса, под ограничением времени готовности.
    """
    config = path.with_name(path.name + ".conf")
    if not config.is_file():
        return True
    text = config.read_text(encoding="utf-8")
    return re.search(r"executeInTransaction\s*=\s*false", text, re.IGNORECASE) is None


def check_service(directory: Path) -> list[Problem]:
    problems: list[Problem] = []
    seen: dict[int, str] = {}

    for path in sorted(p for p in directory.iterdir() if p.suffix.lower() == ".sql"):
        match = FILENAME.match(path.name)
        if match is None:
            problems.append(
                Problem(path, "M2", "имя не по соглашению проекта «V<число>__<описание>.sql»")
            )
        else:
            version = int(match.group(1))
            if version in seen:
                problems.append(
                    Problem(
                        directory,
                        "M1",
                        f"версия {version} занята дважды — «{seen[version]}» и «{path.name}»: "
                        f"Flyway откажется стартовать с обеими",
                    )
                )
            else:
                seen[version] = path.name

        # Содержимое проверяется всегда, даже у файла с неверным именем: два замечания честнее
        # одного, а разбор от имени не зависит.
        problems.extend(check_sql(path, path.read_text(encoding="utf-8"), runs_in_transaction(path)))

    return problems


def main() -> int:
    root = Path(__file__).resolve().parent.parent
    # Каталоги сборки исключены. Туда попадают копии миграций, и устаревшая копия проверялась бы
    # как настоящая: файл, переименованный в исходниках, ещё лежит в target и даёт замечание,
    # которого в репозитории уже нет.
    directories = sorted(
        p
        for p in root.glob("services/**/db/migration")
        if p.is_dir() and not {"target", "build", "out"} & set(p.parts)
    )
    # Схема ClickHouse версионируется теми же правилами имён и той же уникальностью номера
    # (`deploy/compose/clickhouse/apply-migrations.sh`). Правила M3–M5 к ней не относятся —
    # это не Postgres, — но M1 и M2 значат ровно то же самое.
    clickhouse = root / "deploy" / "compose" / "clickhouse" / "migrations"
    if clickhouse.is_dir():
        directories.append(clickhouse)
    if not directories:
        print("не найдено ни одного каталога миграций", file=sys.stderr)
        return 1

    problems: list[Problem] = []
    for directory in directories:
        problems.extend(check_service(directory))

    if problems:
        for problem in problems:
            print(f"  ✗ {problem.render()}", file=sys.stderr)
        return 1

    total = sum(len([p for p in d.iterdir() if p.suffix.lower() == ".sql"]) for d in directories)
    print(f"миграции: {total} файлов в {len(directories)} каталогах, замечаний нет")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
