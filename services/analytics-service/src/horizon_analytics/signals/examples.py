"""Доказательная база для имён, предложенных моделью: настоящие работы из OpenAlex.

Почему отдельно от пакетного сбора. Признаки собираются для всех кандидатов подряд — их сотни, и
каждый лишний запрос умножается на эту сотню. Работы нужны единицам: только тем предложенным
именам, которые уже прошли проверку измеренными свидетельствами. Спрашивать их в общем пакете
значило бы платить за сто пятьдесят запросов ради пяти ответов и заодно обнулить кэш признаков,
который собирался часами.

Кэш общий с признаками — тот же каталог, своё имя источника (`openalex_examples`), своя версия
разбора. Поэтому прогон на предзаполненном кэше не ходит в сеть и здесь тоже, а `cache_only`
означает ровно то же: нет в кэше — нет работ, тема останется без доказательств и не попадёт в
отчёт, а анализ пойдёт дальше.
"""

from __future__ import annotations

import logging
import time
from collections.abc import Iterable, Sequence
from datetime import date
from pathlib import Path
from typing import Any

from horizon_analytics.signals.cache import DEFAULT_CACHE_DIR, SignalCache
from horizon_analytics.signals.collector import (
    DEFAULT_YEARS,
    _SourceSpec,
    _term_deadline,
    collect_term,
)
from horizon_analytics.signals.sources import openalex

__all__ = ["collect_examples"]

_LOG = logging.getLogger(__name__)


def collect_examples(
    terms: Iterable[str],
    *,
    cache_dir: Path = DEFAULT_CACHE_DIR,
    years: Sequence[int] = DEFAULT_YEARS,
    today: date | None = None,
    limit: int = openalex.DEFAULT_EXAMPLE_LIMIT,
    cache_only: bool = False,
    budget_seconds: float | None = None,
    term_budget_seconds: float | None = None,
) -> dict[str, list[dict[str, Any]]]:
    """Собрать по несколько работ на фразу; фраза без работ в ответе не появится.

    Последовательно и без пула: имён здесь единицы, а темп источника всё равно один на всех — пул
    добавил бы сложность, не добавив скорости. Отказ источника оседает в поле, как и в пакетном
    сборе, и наверх не летит: тема без доказательств просто не будет опубликована.

    Потолки времени те же и по той же причине: этап анализа обязан кончиться. Кончился бюджет —
    оставшиеся имена остаются без доказательной базы и не публикуются, а не ждут.
    """
    wanted = [term.strip() for term in dict.fromkeys(terms) if term and term.strip()]
    if not wanted:
        return {}
    day = today or date.today()
    spec = _SourceSpec(
        name=openalex.EXAMPLES_NAME,
        version=openalex.EXAMPLES_VERSION,
        pace=openalex.make_pace(),
        workers=1,
        run=lambda fetcher, term: openalex.collect_examples(
            fetcher, term, years=years, limit=limit
        ),
    )
    cache = SignalCache(cache_dir)
    deadline = (
        time.monotonic() + budget_seconds
        if budget_seconds is not None and budget_seconds > 0
        else None
    )
    fetcher = spec.make_fetcher()
    out: dict[str, list[dict[str, Any]]] = {}
    try:
        for term in wanted:
            if deadline is not None and time.monotonic() >= deadline:
                _LOG.warning(
                    "бюджет сбора доказательной базы исчерпан: собрано %d имён из %d",
                    len(out),
                    len(wanted),
                )
                break
            try:
                result = collect_term(
                    spec,
                    term,
                    cache=cache,
                    fetcher=fetcher,
                    today=day,
                    cache_only=cache_only,
                    deadline=_term_deadline(deadline, term_budget_seconds),
                )
            # Та же причина, что и в пакетном сборе: неожиданное исключение чужого разборщика не
            # вправе унести с собой остальные имена.
            except Exception as error:
                _LOG.warning("%s/%s: неожиданный отказ разбора: %s", spec.name, term, error)
                continue
            if not result.ok:
                _LOG.info("%s/%s: %s", spec.name, term, result.error)
                continue
            works = result.data.get("works")
            if isinstance(works, list) and works:
                out[term] = [row for row in works if isinstance(row, dict)]
    finally:
        fetcher.close()
    return out
