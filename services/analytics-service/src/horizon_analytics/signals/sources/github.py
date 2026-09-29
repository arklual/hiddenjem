"""GitHub: когда появились репозитории про технологию и сколько у них звёзд.

**Источник медленный и в живом анализе неприменим.** Десять поисковых запросов в минуту без
токена — это около минуты на термин, то есть три часа на двести кандидатов; поэтому он помечен
:data:`~horizon_analytics.signals.collector.SLOW_SOURCES` и выключается флагом ``--fast``. С
``HORIZON_GITHUB_TOKEN`` лимит втрое выше, но и тогда он остаётся самым дорогим из шести.

Лимит поиска без токена — десять запросов в минуту, и он определяет всю форму сбора. Наивный путь
(девять запросов «репозитории, созданные в году N» плюс отдельные запросы за звёздами) стоит
минуту на термин; сто терминов — полтора часа только здесь. Поэтому путей два:

* **узкий термин** (репозиториев немного) — один запрос выдаёт всю выборку, годы и звёзды
  считаются по ней точно и стоят одно обращение вместо десяти;
* **широкий термин** — выборку целиком не выдадут (поиск GitHub отдаёт максимум тысячу записей),
  поэтому годовой ряд берётся девятью счётчиками ``total_count``, которые точны и выше тысячи,
  а звёзды суммируются по первой сотне самых звёздных.

Во втором случае сумма звёзд — нижняя оценка, и запись об этом лежит рядом с числом
(``stars_exact``), а не в комментарии: без неё модель однажды сравнит точную сумму узкого термина
с усечённой суммой широкого.
"""

from __future__ import annotations

import os
import time
from collections.abc import Mapping, Sequence
from typing import Any

import httpx

from horizon_analytics.signals.http import Fetcher, Pace

__all__ = ["NAME", "VERSION", "collect", "has_token", "make_pace", "parse_page", "token_headers"]

NAME = "github"
VERSION = 1

_SEARCH = "https://api.github.com/search/repositories"
_PAGE = 100
#: До скольких репозиториев идём точным путём. Пять страниц — половина минутной квоты без токена:
#: дальше выгоднее девять счётчиков, чем продолжать листать.
DEFAULT_EXHAUSTIVE_LIMIT = 500


def has_token() -> bool:
    """Есть ли в окружении ``HORIZON_GITHUB_TOKEN``."""
    return bool(os.environ.get("HORIZON_GITHUB_TOKEN"))


def token_headers() -> dict[str, str]:
    """Заголовки авторизации, если токен задан."""
    token = os.environ.get("HORIZON_GITHUB_TOKEN")
    headers = {"Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    return headers


def make_pace() -> Pace:
    """Шесть секунд без токена (10 запросов в минуту), две с токеном (30 в минуту)."""
    return Pace(2.0 if has_token() else 6.0)


def collect(
    fetcher: Fetcher,
    term: str,
    *,
    years: Sequence[int],
    exhaustive_limit: int = DEFAULT_EXHAUSTIVE_LIMIT,
) -> dict[str, Any]:
    """Годовой ряд созданных репозиториев и сумма звёзд."""
    first = _search(fetcher, f'"{term}"', page=1, sort="stars")
    total = int(first.get("total_count") or 0)
    stars, repos = parse_page(first)

    if total <= exhaustive_limit:
        page = 2
        while len(repos) < total and (page - 1) * _PAGE < total:
            more = _search(fetcher, f'"{term}"', page=page, sort="stars")
            page_stars, page_repos = parse_page(more)
            if not page_repos:
                break
            stars += page_stars
            repos.extend(page_repos)
            page += 1
        by_year = _bucket(repos, years)
        stars_exact = len(repos) >= total
    else:
        by_year = {}
        for year in years:
            query = f'"{term}" created:{year}-01-01..{year}-12-31'
            by_year[str(year)] = int(
                _search(fetcher, query, page=1, sort=None).get("total_count") or 0
            )
        stars_exact = False

    window_total = sum(by_year.values())
    recent = [str(year) for year in sorted(years)[-2:]]
    return {
        "repos_by_year": by_year,
        "repos_window_total": window_total,
        "repos_all_time": total,
        "recent_two_year_share": (
            sum(by_year[year] for year in recent) / window_total if window_total else None
        ),
        "stars_total": stars,
        "stars_exact": stars_exact,
        "sampled_repos": len(repos),
        "authenticated": has_token(),
    }


def _search(fetcher: Fetcher, query: str, *, page: int, sort: str | None) -> Mapping[str, Any]:
    """Один поисковый запрос; при исчерпанном лимите отодвигает темп до сброса окна."""
    params: dict[str, Any] = {"q": query, "per_page": _PAGE, "page": page}
    if sort:
        params["sort"] = sort
        params["order"] = "desc"
    response = fetcher.get(_SEARCH, params=params)
    _respect_limit(fetcher, response)
    payload = response.json()
    return payload if isinstance(payload, Mapping) else {}


def _respect_limit(fetcher: Fetcher, response: httpx.Response) -> None:
    """Если квота выбрана до нуля, ждём сброса окна, а не следующие 403."""
    remaining = response.headers.get("x-ratelimit-remaining")
    reset = response.headers.get("x-ratelimit-reset")
    if remaining == "0" and reset:
        try:
            fetcher.pace.defer(max(0.0, float(reset) - time.time()) + 1.0)
        except ValueError:
            fetcher.pace.defer(60.0)


def parse_page(payload: Mapping[str, Any]) -> tuple[int, list[dict[str, Any]]]:
    """Сумма звёзд страницы и её репозитории в виде «год создания → звёзды»."""
    stars = 0
    repos: list[dict[str, Any]] = []
    for item in payload.get("items") or []:
        count = int(item.get("stargazers_count") or 0)
        stars += count
        created = str(item.get("created_at") or "")
        repos.append({"created_year": created[:4], "stars": count})
    return stars, repos


def _bucket(repos: Sequence[Mapping[str, Any]], years: Sequence[int]) -> dict[str, int]:
    """Раскладывает репозитории по годам окна."""
    by_year = {str(year): 0 for year in years}
    for repo in repos:
        key = str(repo.get("created_year"))
        if key in by_year:
            by_year[key] += 1
    return by_year
