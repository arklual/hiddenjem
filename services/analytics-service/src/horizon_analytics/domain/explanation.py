"""Почему тема — слабый сигнал и почему у неё такая уверенность: объяснение для карточки.

ТЗ требует на странице инсайта «объяснение статуса слабого сигнала, причины присвоения
соответствующей уверенности модели» и ключевые предикторы. Объяснение собирается из того, что
движок действительно посчитал, — голосов экспертной стадии, вкладов в балл, расчёта уверенности и
источников карточки, — а не пишется заново: каждая строка сверяется с числами отчёта.
"""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any, Final

from horizon_analytics.domain.evidence import EvidenceSelection
from horizon_analytics.domain.models import EmergenceResult
from horizon_analytics.domain.signal_scoring import SignalScore

__all__ = ["explain"]

#: Роли экспертной стадии — какой вопрос жюри каждая проверяет.
ROLE_TITLES: Final[Mapping[str, str]] = {
    "domain": "по теме запроса",
    "market": "ранняя стадия, не мейнстрим",
    "evidence": "подтверждение источниками",
}

#: Признаки модели внешних признаков — словами читателя.
FEATURE_TITLES: Final[Mapping[str, str]] = {
    "corpus.documents": "число документов о теме",
    "corpus.documents_last_year": "документы за последний год",
    "corpus.recent_two_year_share": "доля публикаций последних двух лет",
    "corpus.growth_slope": "рост числа публикаций",
    "openalex.works_window_total": "научные работы за окно наблюдения",
    "openalex.works_last_year": "научные работы за последний год",
    "openalex.recent_two_year_share": "доля свежих научных работ",
    "openalex.distinct_institutions": "число организаций-авторов",
    "openalex.top_institution_share": "доля ведущей организации",
    "hackernews.mentions_window_total": "обсуждения разработчиков",
    "hackernews.mentions_last_year": "обсуждения разработчиков за последний год",
    "hackernews.recent_two_year_share": "доля свежих обсуждений",
    "wikipedia.exists": "статья в Википедии",
    "wikipedia.age_days": "возраст статьи в Википедии",
    "wikipedia.size_bytes": "объём статьи в Википедии",
    "wikipedia.pageviews_12m": "интерес читателей Википедии",
}

#: Слагаемые балла в пунктах — не признаки модели, а части самого балла.
POINT_TERMS: Final[Mapping[str, str]] = {
    "methodology_score": "методология зарождения",
    "alphaxiv_papers": "статьи alphaXiv",
    "independent_sources": "независимые площадки",
    "llm_rerank_and_experts": "переранжирование и экспертная проверка",
}


def _points(value: float) -> str:
    return f"{value:+.1f}".replace(".", ",")


def _plural(count: int, one: str, few: str, many: str) -> str:
    tail = count % 100
    if 11 <= tail <= 14:
        return many
    return one if count % 10 == 1 else few if 2 <= count % 10 <= 4 else many


def _confidence(result: EmergenceResult) -> str:
    """Уверенность словами: из чего она сложилась — по тем же четырём частям, что в расчёте."""
    percent = round(result.confidence * 100)
    diagnostics = result.confidence_diagnostics or {}
    try:
        documents = int(diagnostics["documentFrequency"])
        classes = int(diagnostics["sourceClassCount"])
        periods = int(diagnostics["periodsWithData"])
        span = int(diagnostics["spanReference"])
        fit = float(diagnostics["fit"])
        threshold = float(diagnostics["threshold"])
    except (KeyError, TypeError, ValueError):
        return f"{percent} %: {result.confidence_explanation}" if result.confidence_explanation else ""
    growth = (
        "рост по годам ещё не читается — точек слишком мало"
        if fit <= 0.0
        else f"рост по годам ложится на прямую на {round(fit * 100)} %"
    )
    text = (
        f"{percent} %. Из чего она сложилась: документов о теме — {documents}; классов источников — {classes}; "
        f"{growth}; данные есть за {periods} {_plural(periods, 'год', 'года', 'лет')} из {span}."
    )
    if result.confidence < threshold:
        text += (
            f" Это ниже порога {round(threshold * 100)} %: доказательств пока мало, что и ожидаемо для "
            "слабого сигнала, — поэтому тема помечена «низкая доказательная база»."
        )
    return text


def explain(
    result: EmergenceResult,
    evidence: EvidenceSelection,
    *,
    signal: SignalScore | None = None,
    jury: Mapping[str, Any] | None = None,
) -> tuple[tuple[str, str], ...]:
    """Строки «заголовок — пояснение» для карточки темы, в порядке чтения."""
    items: list[tuple[str, str]] = []

    votes = (jury or {}).get("votes") or {}
    if "corpus_labels" in votes:
        _, reason = votes["corpus_labels"]
        items.append((
            "Экспертная проверка",
            "Данные размечены через LLM по четырём критериям жюри — технология, по теме, ранняя "
            "стадия, подтверждение источниками — и тема прошла все четыре" + (f": {reason}" if reason else "."),
        ))
    elif votes:
        parts = [
            f"{ROLE_TITLES.get(role, role)} — {'да' if bool(vote[0]) else 'нет'}"
            + (f" ({vote[1]})" if len(vote) > 1 and vote[1] else "")
            for role, vote in votes.items()
        ]
        items.append((
            "Экспертная проверка",
            f"«Да» у {int((jury or {}).get('yes', 0))} из 3 экспертов: " + "; ".join(parts) + ".",
        ))

    if signal is not None:
        terms = {item.name: item.contribution for item in signal.contributions if item.name in POINT_TERMS}
        model_points = signal.score - sum(terms.values())
        parts = [f"{POINT_TERMS['methodology_score']} {_points(terms.get('methodology_score', 0.0))}"]
        parts.append(
            f"модель внешних признаков {_points(model_points)}"
            if signal.external
            else f"базовая часть модели {_points(model_points)} (внешних данных о теме не нашлось)"
        )
        parts += [
            f"{POINT_TERMS[name]} {_points(value)}"
            for name, value in terms.items()
            if name != "methodology_score" and abs(value) >= 0.05
        ]
        features = sorted(
            (item for item in signal.contributions if item.name in FEATURE_TITLES and abs(item.contribution) > 1e-6),
            key=lambda item: -abs(item.contribution),
        )[:3]
        drivers = "; ".join(
            f"{FEATURE_TITLES[item.name]} {'поднимает' if item.contribution > 0 else 'снижает'}" for item in features
        )
        items.append((
            "Из чего сложился балл",
            f"Балл {signal.score:.0f} из 100: " + ", ".join(parts) + "."
            + (f" В модели сильнее всего: {drivers}." if drivers else ""),
        ))

    confidence = _confidence(result)
    if confidence:
        items.append(("Почему такая уверенность", confidence))

    documents = evidence.documents
    if documents:
        hosts = {document.url.split("/")[2] for document in documents if "://" in document.url}
        latest = max(document.published_on for document in documents)
        independent = sum(1 for item in evidence.items if item.independent)
        items.append((
            "Источники",
            f"{len(documents)} источников на {len(hosts)} площадках, из них независимых — {independent}; "
            f"последний датирован {latest:%d.%m.%Y}.",
        ))
    return tuple(items)
