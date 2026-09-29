"""Патенты: источника, доступного без ключа, на сегодня нет.

Проверено 2026-09-19, живыми запросами:

* ``api.patentsview.org`` (старый API) — 301 на ``search.patentsview.org``, который без
  заголовка ``X-Api-Key`` соединение не отдаёт. Ключ бесплатный, но выдаётся по регистрации.
* ``data.uspto.gov`` (Open Data Portal, куда теперь ведут все старые адреса ``developer.uspto.gov``
  — ``ibd-api``, ``ptab-api``) — HTTP 401 без ключа.
* ``ops.epo.org`` (EPO Open Patent Services) — HTTP 403, нужен OAuth-ключ; бесплатный тариф тоже
  по регистрации.
* ``patents.google.com/xhr/query`` — отвечает 200 **и запрещён их же robots.txt**: там
  ``Disallow: /*`` и разрешены только ``/$``, ``/advanced$``, ``/patent/``, ``/sitemap/``.
  Работающий ответ не делает обращение разрешённым.
* Espacenet и Patentscope бесплатного поискового API не публикуют вовсе; их веб-интерфейс —
  HTML под ToS, запрещающими автоматический сбор.

Поэтому поле остаётся пустым, а не заполняется числом «из похожего источника». Выдуманный
признак хуже отсутствующего: отсутствующий видно, выдуманный модель примет за правду.

Что есть, если однажды появится ключ или место под выгрузку: PatentsView Search API и USPTO ODP
(бесплатные ключи по регистрации), EPO OPS (бесплатный тариф), а также полные выгрузки
PatentsView и Google Patents Public Data в BigQuery — они без ключа, но это десятки гигабайт,
то есть другой режим работы, а не запрос на термин.
"""

from __future__ import annotations

from typing import Any

from horizon_analytics.signals.http import Fetcher, Pace

__all__ = ["NAME", "REASON", "VERSION", "collect", "make_pace"]

NAME = "patents"
VERSION = 1

REASON = (
    "нет патентного источника с посрочным поиском, доступного без ключа и без нарушения "
    "robots.txt/ToS (проверено 2026-09-19: PatentsView и USPTO ODP — ключ, EPO OPS — OAuth, "
    "Google Patents — запрещён robots.txt)"
)


def make_pace() -> Pace:
    """Сеть не используется; темп нужен только для единообразия сборки источников."""
    return Pace(0.0)


def collect(fetcher: Fetcher, term: str) -> dict[str, Any]:
    """Возвращает явно пустой признак с причиной.

    Источник не убран из списка нарочно: пустое поле с объяснением — часть результата
    исследования, и когда ключ появится, места в записи и в кэше менять не придётся.
    """
    return {"available": False, "patents_by_year": None, "reason": REASON}
