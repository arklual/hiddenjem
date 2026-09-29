"""arXiv: сколько препринтов по годам.

arXiv не умеет группировать, поэтому год спрашивается отдельным запросом — девять запросов на
термин, по три секунды между ними, как просит их же руководство по API. Из ответа берётся только
``opensearch:totalResults``: счётчик приходит в первой же странице, качать сами записи не нужно.

Отдельная мелочь, на которую легко потратить час: arXiv отвечает 406, если заголовки запроса
выглядят «библиотечными». Явный простой User-Agent (см. :mod:`horizon_analytics.signals.http`) и
httpx эту проблему снимают.

Чего трёхсекундный шаг **не** решает: лимит стоит на адресе, а шаг — на процессе. Два сбора с
одной машины дают шесть запросов на те же три секунды, и arXiv отвечает 429 без заголовка
``Retry-After`` — проверено 2026-09-19, когда параллельный сбор по соседнему списку терминов увёл
источник в отказ на девяти терминах из двадцати. Поведение при этом правильное: поле остаётся
пустым с причиной, а не заполняется нулём, — и дособирается позже флагом ``--retry-failed``,
когда лимит отпустит. Полноценное решение — общий на машину ограничитель, и его тут нет.
"""

from __future__ import annotations

from collections.abc import Sequence
from typing import Any
from xml.etree import ElementTree

from horizon_analytics.signals.http import Fetcher, Pace, SourceUnavailableError

__all__ = ["NAME", "VERSION", "collect", "make_pace", "parse_total"]

NAME = "arxiv"
VERSION = 1

_QUERY = "https://export.arxiv.org/api/query"
_OPENSEARCH = "{http://a9.com/-/spec/opensearch/1.1/}totalResults"


def make_pace() -> Pace:
    """Три секунды между обращениями — прямое требование руководства arXiv по API."""
    return Pace(3.0)


def collect(fetcher: Fetcher, term: str, *, years: Sequence[int]) -> dict[str, Any]:
    """Годовой ряд препринтов по точной фразе."""
    by_year: dict[str, int] = {}
    for year in years:
        window = f"submittedDate:[{year}01010000 TO {year}12312359]"
        response = fetcher.get(
            _QUERY,
            params={
                "search_query": f'all:"{term}" AND {window}',
                "start": 0,
                "max_results": 1,
            },
        )
        by_year[str(year)] = parse_total(response.text)
    window_total = sum(by_year.values())
    recent = [str(year) for year in sorted(years)[-2:]]
    recent_total = sum(by_year[year] for year in recent)
    return {
        "preprints_by_year": by_year,
        "preprints_window_total": window_total,
        "recent_two_year_share": (recent_total / window_total) if window_total else None,
    }


def parse_total(xml: str) -> int:
    """Достаёт ``opensearch:totalResults`` из ленты Atom.

    Raises:
        SourceUnavailableError: если ленты нет или счётчика в ней нет — молча вернуть ноль нельзя,
            это превратило бы поломку arXiv в утверждение «препринтов не существует».
    """
    try:
        feed = ElementTree.fromstring(xml)
    except ElementTree.ParseError as exc:
        raise SourceUnavailableError(f"arXiv вернул не XML: {exc}") from exc
    node = feed.find(_OPENSEARCH)
    if node is None or not (node.text or "").strip():
        raise SourceUnavailableError("в ленте arXiv нет opensearch:totalResults")
    return int((node.text or "0").strip())
