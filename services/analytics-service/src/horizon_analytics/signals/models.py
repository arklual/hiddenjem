"""Записи, в которых живут собранные признаки.

Признаки собираются для того, чтобы на них училась модель, поэтому запись устроена в два слоя.
Нижний — сырые ответы источников, разобранные, но не свёрнутые: по ним всегда видно, откуда
взялось число, и их можно перетолковать, не ходя в сеть заново. Верхний — плоский словарь
``features``: имена колонок будущей обучающей таблицы.

Отдельного слоя «источник отказал» нет по той же причине: отказ — это тоже наблюдение, и он
хранится рядом с данными (``ok``/``error``), а не теряется в журнале. Модель должна уметь отличить
«репозиториев ноль» от «GitHub не ответил», и для этого в ``features`` в первом случае стоит ноль,
а во втором ключа нет вовсе.
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass, field
from datetime import date
from typing import Any

__all__ = ["SourceResult", "TermSignals"]


@dataclass(frozen=True, slots=True)
class SourceResult:
    """Что один источник рассказал про один термин.

    Attributes:
        source: Имя источника (``openalex``, ``arxiv``, ...).
        ok: Удалось ли получить ответ. При ``False`` ``data`` пуст, а причина лежит в ``error``.
        data: Разобранный ответ. Ключи свои у каждого источника.
        error: Человекочитаемая причина отказа.
        collected_on: Дата сбора — по ней видно, насколько запись устарела.
        version: Версия разбора этого источника. Кэш с другой версией не переиспользуется.
    """

    source: str
    ok: bool
    collected_on: date
    version: int
    data: Mapping[str, Any] = field(default_factory=dict)
    error: str | None = None

    def to_json(self) -> dict[str, Any]:
        """Представление для JSONL: даты — строками, ничего не теряется."""
        return {
            "ok": self.ok,
            "collected_on": self.collected_on.isoformat(),
            "version": self.version,
            "data": dict(self.data),
            "error": self.error,
        }

    @classmethod
    def from_json(cls, source: str, payload: Mapping[str, Any]) -> SourceResult:
        """Обратное преобразование — им читается кэш."""
        return cls(
            source=source,
            ok=bool(payload["ok"]),
            collected_on=date.fromisoformat(str(payload["collected_on"])),
            version=int(payload["version"]),
            data=dict(payload.get("data") or {}),
            error=payload.get("error"),
        )


@dataclass(frozen=True, slots=True)
class TermSignals:
    """Все признаки одного термина — по одной записи на строку JSONL."""

    term: str
    area: str | None
    collected_on: date
    sources: Mapping[str, SourceResult]

    @property
    def failures(self) -> tuple[str, ...]:
        """Источники, которые не ответили. Пустой кортеж — сбор полный."""
        return tuple(sorted(name for name, result in self.sources.items() if not result.ok))

    def features(self) -> dict[str, float]:
        """Плоские числовые признаки: имена колонок будущей обучающей таблицы.

        Ключ отсутствует, если источник не ответил, — «нет данных» не то же самое, что ноль, и
        подменять одно другим здесь нельзя: именно на этой подмене модель научится считать
        недоступность источника признаком незрелости технологии.
        """
        flat: dict[str, float] = {}
        for name, result in self.sources.items():
            if not result.ok:
                continue
            for key, value in _flatten(result.data):
                flat[f"{name}.{key}"] = value
        return flat

    def to_json(self) -> dict[str, Any]:
        """Строка JSONL: сырые разборы плюс плоские признаки."""
        return {
            "term": self.term,
            "area": self.area,
            "collected_on": self.collected_on.isoformat(),
            "failures": list(self.failures),
            "sources": {name: result.to_json() for name, result in sorted(self.sources.items())},
            "features": self.features(),
        }


def _flatten(data: Mapping[str, Any], prefix: str = "") -> list[tuple[str, float]]:
    """Разворачивает вложенные словари в пары «путь → число».

    Нечисловые значения (заголовок статьи, даты, признаки полноты) намеренно не попадают в
    обучающую таблицу: они нужны человеку, который проверяет число, а не модели.
    """
    out: list[tuple[str, float]] = []
    for key, value in data.items():
        path = f"{prefix}{key}"
        if isinstance(value, Mapping):
            out.extend(_flatten(value, f"{path}."))
        elif isinstance(value, bool | int | float):
            out.append((path, float(value)))
    return out
