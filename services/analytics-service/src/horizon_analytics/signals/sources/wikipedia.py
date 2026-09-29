"""Википедия: есть ли у технологии статья, давно ли она есть и кто её читает.

Статья в Википедии — поздний признак: её заводят, когда о технологии уже спорят за пределами
лаборатории. Поэтому здесь важна не столько сама статья, сколько дата её появления (первая
ревизия) и поток читателей за последний год.

Статья ищется **точным заголовком**, не поиском. Поиск по фразе «photonic processors» вернёт
«Optical computing» — статью про соседнюю тему, и признак «у технологии есть своя статья» стал бы
признаком «в Википедии есть что-то похожее». Перенаправления учитываются (``redirects=1``): это
по-прежнему та же статья под другим именем.
"""

from __future__ import annotations

import calendar
from collections.abc import Mapping, Sequence
from datetime import date
from typing import Any

from horizon_analytics.signals.http import Fetcher, Pace

__all__ = [
    "NAME",
    "VERSION",
    "article_age_days",
    "collect",
    "make_pace",
    "pageview_window",
    "parse_article",
    "parse_pageviews",
]

NAME = "wikipedia"
VERSION = 2

_API = "https://en.wikipedia.org/w/api.php"
_PAGEVIEWS = (
    "https://wikimedia.org/api/rest_v1/metrics/pageviews/per-article/en.wikipedia/all-access/user"
)


def make_pace() -> Pace:
    """Пять запросов в секунду.

    Политика Викимедиа для анонимного чтения считает предел сотнями запросов в секунду на адрес;
    пять — заведомо вежливо и при этом не делает Википедию узким местом пакета: на двухстах
    кандидатах полсекунды между запросами стоили бы больше, чем весь остальной сбор.
    """
    return Pace(0.2)


def collect(fetcher: Fetcher, term: str, *, today: date) -> dict[str, Any]:
    """Факт существования статьи, дата её создания, размер и просмотры за 12 месяцев."""
    response = fetcher.get(
        _API,
        params={
            "action": "query",
            "format": "json",
            "formatversion": 2,
            "prop": "info|revisions",
            "titles": term,
            "redirects": 1,
            "rvlimit": 1,
            "rvdir": "newer",
            "rvprop": "timestamp",
        },
    )
    article = {**parse_article(response.json())}
    article["age_days"] = article_age_days(article["created_on"], today)
    if not article["exists"]:
        return {**article, "pageviews_12m": None, "pageview_months": 0}

    start, end = pageview_window(today)
    title = str(article["title"]).replace(" ", "_")
    views = fetcher.get(f"{_PAGEVIEWS}/{title}/monthly/{start}/{end}").json()
    return {**article, **parse_pageviews(views)}


def article_age_days(created_on: str | None, today: date) -> int | None:
    """Сколько дней статье.

    Дата создания в обучающую таблицу не попадает — она строка, — а возраст попадает, и именно он
    отвечает на вопрос, ради которого источник взят: Википедия заводит статью, когда о технологии
    уже спорят вне лаборатории, поэтому «статье полгода» и «статье двадцать лет» — это два разных
    места жизненного цикла при одинаковом ответе «статья есть».
    """
    if not created_on:
        return None
    try:
        return (today - date.fromisoformat(created_on)).days
    except ValueError:
        return None


def pageview_window(today: date) -> tuple[str, str]:
    """Последние двенадцать **полных** месяцев.

    Текущий месяц исключён намеренно: он неполон, и включив его, мы получили бы ряд, который
    меняется каждый день, — сравнивать такие числа между терминами, собранными в разные дни,
    нельзя.
    """
    if today.month > 1:
        end_year, end_month = today.year, today.month - 1
    else:
        end_year, end_month = today.year - 1, 12
    start_month = end_month % 12 + 1
    start_year = end_year if start_month == 1 else end_year - 1
    last_day = calendar.monthrange(end_year, end_month)[1]
    return f"{start_year:04d}{start_month:02d}01", f"{end_year:04d}{end_month:02d}{last_day:02d}"


def parse_article(payload: Mapping[str, Any]) -> dict[str, Any]:
    """Разбирает ответ ``action=query``: есть ли страница, её размер и первая ревизия."""
    pages: Sequence[Mapping[str, Any]] = (payload.get("query") or {}).get("pages") or []
    if not pages:
        return {"exists": False, "title": None, "created_on": None, "size_bytes": None}
    page = pages[0]
    if page.get("missing") or page.get("invalid"):
        return {
            "exists": False,
            "title": page.get("title"),
            "created_on": None,
            "size_bytes": None,
        }
    revisions = page.get("revisions") or []
    created = str(revisions[0]["timestamp"])[:10] if revisions else None
    return {
        "exists": True,
        "title": page.get("title"),
        "created_on": created,
        "size_bytes": int(page.get("length") or 0),
    }


def parse_pageviews(payload: Mapping[str, Any]) -> dict[str, Any]:
    """Сумма просмотров и число месяцев, за которые данные вообще есть.

    Число месяцев хранится рядом с суммой, потому что у молодой статьи ряд короче года, и без
    этого признака её сумма выглядела бы как провал интереса, а не как отсутствие истории.
    """
    items = payload.get("items") or []
    return {
        "pageviews_12m": sum(int(item.get("views") or 0) for item in items),
        "pageview_months": len(items),
    }
