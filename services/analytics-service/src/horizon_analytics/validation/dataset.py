"""Чтение размеченного датасета и построение поисковых формулировок.

Датасет методологов приходит таблицей, где название технологии написано по-русски и развёрнуто:
«Квантово-инспирированное сжатие моделей для запуска LLM на edge-железе». Открытые источники —
англоязычные, и искать в них эту строку целиком бессмысленно. Нужна короткая техническая фраза, по
которой технологию действительно ищут.

**Из датасета берутся только название и область.** Колонки «Почему это слабый сигнал», «Стадия
развития», «Тренд упоминаний» и «Балл» не читаются вовсе, и это главное решение модуля. Они
содержат готовый вывод методолога — тот самый, который система должна получить самостоятельно.
Признак, построенный по ним, измерял бы умение читать чужой ответ.

Формулировки сохраняются в файл и переиспользуются. Причина не в экономии: перевод делается
моделью, и без сохранения два прогона на одном датасете искали бы разное, а сравнить их было бы
нельзя. Файл лежит в репозитории, и любой может увидеть, что именно искалось.
"""

from __future__ import annotations

import json
import re
import xml.etree.ElementTree as ElementTree
import zipfile
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path

__all__ = ["DatasetRow", "NegativeRow", "load_negatives", "load_positives", "search_phrase"]

_NS = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"

#: Заголовки колонок, которые модуль готов прочитать. Всё остальное игнорируется намеренно.
_COLUMN_NAME = "Технология (слабый сигнал)"
_COLUMN_AREA = "Область"

#: Латинский фрагмент: слова из букв, цифр и дефисов, возможно через пробел.
_LATIN = re.compile(r"[A-Za-z][A-Za-z0-9\-\.]*(?:\s+[A-Za-z][A-Za-z0-9\-\.]*)*")

#: Фрагменты, которые латиницей написаны, но технологию не называют.
_LATIN_STOPWORDS = frozenset(
    {
        "ai",
        "it",
        "the",
        "of",
        "and",
        "as",
        "in",
        "for",
        "to",
        "a",
        "pc",
        "us",
        "eu",
        "ru",
        "e",
        "mail",
        "e-mail",
    }
)


@dataclass(frozen=True, slots=True)
class DatasetRow:
    """Одна размеченная строка: название, область и формулировка для поиска."""

    number: int
    name: str
    area: str
    #: Фраза, по которой технология ищется в открытых источниках.
    query: str
    #: Откуда взялась фраза: ``latin`` — вырезана из названия, ``model`` — переведена моделью.
    query_origin: str


@dataclass(frozen=True, slots=True)
class NegativeRow:
    """Строка отрицательного контроля."""

    query: str
    area: str
    kind: str
    reason: str


def _shared_strings(archive: zipfile.ZipFile) -> list[str]:
    try:
        root = ElementTree.fromstring(archive.read("xl/sharedStrings.xml"))
    except KeyError:
        return []
    return [
        "".join(node.text or "" for node in item.iter(f"{_NS}t"))
        for item in root.findall(f"{_NS}si")
    ]


def _cells(archive: zipfile.ZipFile, strings: Sequence[str]) -> list[dict[str, str]]:
    sheet = ElementTree.fromstring(archive.read("xl/worksheets/sheet1.xml"))
    rows: list[dict[str, str]] = []
    for row in sheet.iter(f"{_NS}row"):
        cells: dict[str, str] = {}
        for cell in row.findall(f"{_NS}c"):
            reference = cell.get("r") or ""
            column = re.match(r"[A-Z]+", reference)
            if column is None:
                continue
            inline = cell.find(f"{_NS}is")
            value = cell.find(f"{_NS}v")
            if inline is not None:
                text = "".join(node.text or "" for node in inline.iter(f"{_NS}t"))
            elif value is None:
                text = ""
            elif cell.get("t") == "s":
                index = int(value.text or "0")
                text = strings[index] if 0 <= index < len(strings) else ""
            else:
                text = value.text or ""
            cells[column.group()] = text
        rows.append(cells)
    return rows


def search_phrase(name: str) -> tuple[str, str] | None:
    """Вырезать из названия техническую фразу на латинице.

    Возвращает ``(фраза, "latin")`` либо ``None``, если латиницы в названии нет или она ничего не
    называет. Предпочитается самый длинный фрагмент: в «Identity & access management для
    ИИ-агентов (agent identity, scoped permissions)» технологию называет «access management», а не
    «agent».

    Однословные фрагменты принимаются только если это не общее слово: «MCP» и «NPU» — имена,
    «AI» и «PC» — нет.
    """
    candidates: list[str] = []
    for match in _LATIN.finditer(name):
        fragment = match.group().strip(" .-")
        if not fragment:
            continue
        words = fragment.split()
        if len(words) == 1 and words[0].lower() in _LATIN_STOPWORDS:
            continue
        if len(fragment) < 3:
            continue
        candidates.append(fragment)
    if not candidates:
        return None
    # Самый длинный по числу слов, при равенстве — по числу знаков.
    best = max(candidates, key=lambda item: (len(item.split()), len(item)))
    if len(best.split()) == 1 and len(best) <= 3 and not best.isupper():
        return None
    return best, "latin"


def load_positives(
    workbook: Path, *, translations: dict[str, str] | None = None
) -> list[DatasetRow]:
    """Прочитать размеченный датасет: только номер, название и область.

    ``translations`` — сохранённые переводы названий, ключ совпадает с названием. Строка, для
    которой нет ни латинского фрагмента, ни перевода, всё равно возвращается: её формулировкой
    становится само название, а замер обязан показать, что по такой формулировке ничего не
    находится, — молча выбросить строку значило бы улучшить метрику удалением трудных случаев.
    """
    with zipfile.ZipFile(workbook) as archive:
        strings = _shared_strings(archive)
        raw = _cells(archive, strings)

    header_index = None
    columns: dict[str, str] = {}
    for index, cells in enumerate(raw):
        values = {value: key for key, value in cells.items()}
        if _COLUMN_NAME in values and _COLUMN_AREA in values:
            header_index = index
            columns = {"name": values[_COLUMN_NAME], "area": values[_COLUMN_AREA]}
            first = next((key for key, value in cells.items() if value == "№"), None)
            if first is not None:
                columns["number"] = first
            break
    if header_index is None:
        raise ValueError(f"в {workbook} не найдены колонки «{_COLUMN_NAME}» и «{_COLUMN_AREA}»")

    rows: list[DatasetRow] = []
    for position, cells in enumerate(raw[header_index + 1 :], start=1):
        name = (cells.get(columns["name"]) or "").strip()
        if not name:
            continue
        area = (cells.get(columns["area"]) or "").strip()
        number_text = (cells.get(columns.get("number", "")) or "").strip()
        number = int(number_text) if number_text.isdigit() else position
        saved = (translations or {}).get(name)
        if saved:
            query, origin = saved, "model"
        else:
            extracted = search_phrase(name)
            query, origin = extracted if extracted is not None else (name, "name")
        rows.append(
            DatasetRow(number=number, name=name, area=area, query=query, query_origin=origin)
        )
    return rows


def load_negatives(path: Path) -> list[NegativeRow]:
    """Прочитать отрицательный контроль."""
    payload = json.loads(path.read_text(encoding="utf-8"))
    return [
        NegativeRow(
            query=str(item["query"]),
            area=str(item.get("area", "")),
            kind=str(item.get("kind", "MATURE")),
            reason=str(item.get("reason", "")),
        )
        for item in payload.get("items", [])
    ]
