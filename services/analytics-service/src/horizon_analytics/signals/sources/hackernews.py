"""Hacker News: сколько раз технологию упоминали и в каком году — обычно одним запросом.

Algolia не умеет группировать, но у неё есть дешёвый обходной путь: за один запрос она отдаёт до
тысячи попаданий, и если их меньше тысячи — а у любого кандидата на раннюю технологию их меньше, —
годы считаются прямо по ``created_at_i`` из ответа. Девять запросов «сколько в году N» остаются
запасным путём для широких терминов вроде «transformer», где тысячи не хватает.

Поиск идёт по историям и комментариям сразу. Ограничиться историями заманчиво — их меньше и они
«весомее», — но разговор о ранней технологии почти всегда живёт в комментариях к чужому посту, и
отбросив их, мы отбросили бы ровно тот сигнал, ради которого источник взят.
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from datetime import UTC, datetime
from typing import Any

from horizon_analytics.signals.http import Fetcher, Pace, SourceUnavailableError

__all__ = ["NAME", "VERSION", "bucket_hits", "collect", "make_pace", "parse_hits"]

NAME = "hackernews"
VERSION = 3

_SEARCH = "https://hn.algolia.com/api/v1/search"
#: Сколько попаданий Algolia отдаёт за раз. Больше не даст ни страницами, ни параметром.
_PAGE_LIMIT = 1000


def make_pace() -> Pace:
    """Algolia щедра на лимит, но четверть секунды между запросами — разумная вежливость."""
    return Pace(0.25)


def _epoch(year: int) -> int:
    return int(datetime(year, 1, 1, tzinfo=UTC).timestamp())


def collect(fetcher: Fetcher, term: str, *, years: Sequence[int]) -> dict[str, Any]:
    """Годовой ряд упоминаний по точной фразе."""
    ordered = sorted(years)
    start, end = _epoch(ordered[0]), _epoch(ordered[-1] + 1)
    payload = fetcher.get(
        _SEARCH,
        params={
            "query": f'"{term}"',
            "numericFilters": f"created_at_i>={start},created_at_i<{end}",
            "hitsPerPage": _PAGE_LIMIT,
            "advancedSyntax": "true",
            "attributesToRetrieve": "created_at_i",
            "attributesToHighlight": "",
        },
    ).json()
    window_total = parse_hits(payload)

    if window_total <= _PAGE_LIMIT:
        by_year = bucket_hits(payload, ordered)
        year_queries = 0
    else:
        # Тысячи не хватило: широкий термин, годы приходится спрашивать по одному.
        by_year = {}
        for year in ordered:
            year_payload = fetcher.get(
                _SEARCH,
                params={
                    "query": f'"{term}"',
                    "numericFilters": f"created_at_i>={_epoch(year)},created_at_i<{_epoch(year + 1)}",
                    "hitsPerPage": 0,
                    "advancedSyntax": "true",
                },
            ).json()
            by_year[str(year)] = parse_hits(year_payload)
        year_queries = len(ordered)

    recent = [str(year) for year in ordered[-2:]]
    return {
        "mentions_by_year": by_year,
        "mentions_window_total": window_total,
        "recent_two_year_share": (
            sum(by_year[year] for year in recent) / window_total if window_total else None
        ),
        # Сколько лишних запросов стоил термин: ноль — уложились в один ответ, девять — не
        # уложились. Нужно для честного учёта цены пакета, а не как признак технологии.
        "extra_year_queries": year_queries,
    }


def parse_hits(payload: Mapping[str, Any]) -> int:
    """Достаёт ``nbHits``.

    Raises:
        SourceUnavailableError: если поля нет — отсутствие счётчика означает сломанный ответ,
            а не отсутствие упоминаний.
    """
    if "nbHits" not in payload:
        raise SourceUnavailableError("в ответе Algolia нет nbHits")
    return int(payload["nbHits"])


def bucket_hits(payload: Mapping[str, Any], years: Sequence[int]) -> dict[str, int]:
    """Раскладывает попадания по годам их ``created_at_i``."""
    by_year = {str(year): 0 for year in years}
    for hit in payload.get("hits") or []:
        stamp = hit.get("created_at_i")
        if stamp is None:
            continue
        key = str(datetime.fromtimestamp(int(stamp), tz=UTC).year)
        if key in by_year:
            by_year[key] += 1
    return by_year
