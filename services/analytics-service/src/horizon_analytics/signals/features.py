"""Перевод собранных признаков в вектор, который читает модель второго движка.

Слой тонкий и существует ради одного: имена колонок объявлены в домене
(:data:`horizon_analytics.domain.signal_scoring.FEATURES`), а формы ответов — здесь, в пакете,
который эти ответы и разбирает. Пока перевода не было, обучение брало плоский словарь
``TermSignals.features()`` как есть, и любое переименование поля источника молча меняло бы состав
вектора — то есть смысл обученных весов.

Два правила, оба — продолжение правил сбора.

* **Пропуск остаётся пропуском.** Источник не ответил, доля не определена (ноль работ), статьи в
  Википедии нет — ключа в результате нет. Ноль сюда не подставляется нигде: «работ ноль» и
  «OpenAlex молчит» обязаны различаться и в обучении, и в применении.
* **Годовые ряды сворачиваются в последний год окна.** ``works_by_year`` и ``mentions_by_year``
  привязаны к календарю: выборка, собранная в январе, имела бы на колонку меньше, чем собранная в
  декабре, и вектор перестал бы быть вектором постоянной длины. Поэтому из ряда берётся значение
  последнего года — того самого, про который спрашивают «растёт ли сейчас».
"""

from __future__ import annotations

from collections.abc import Mapping
from typing import Any, Final

from horizon_analytics.domain.signal_scoring import EXTERNAL_FEATURES
from horizon_analytics.signals.models import TermSignals

__all__ = ["external_features"]

#: Прямые соответствия «признак модели → путь в разобранном ответе источника».
_DIRECT: Final[Mapping[str, tuple[str, str]]] = {
    "openalex.works_window_total": ("openalex", "works_window_total"),
    "openalex.recent_two_year_share": ("openalex", "recent_two_year_share"),
    "openalex.distinct_institutions": ("openalex", "distinct_institutions"),
    "openalex.top_institution_share": ("openalex", "top_institution_share"),
    "hackernews.mentions_window_total": ("hackernews", "mentions_window_total"),
    "hackernews.recent_two_year_share": ("hackernews", "recent_two_year_share"),
    "wikipedia.exists": ("wikipedia", "exists"),
    "wikipedia.age_days": ("wikipedia", "age_days"),
    "wikipedia.size_bytes": ("wikipedia", "size_bytes"),
    "wikipedia.pageviews_12m": ("wikipedia", "pageviews_12m"),
}

#: Признаки, которые берутся из годового ряда: «признак модели → (источник, ряд)».
_LAST_YEAR: Final[Mapping[str, tuple[str, str]]] = {
    "openalex.works_last_year": ("openalex", "works_by_year"),
    "hackernews.mentions_last_year": ("hackernews", "mentions_by_year"),
}


def external_features(signals: TermSignals) -> dict[str, float]:
    """Вектор внешних признаков одного термина; отсутствующие признаки в него не попадают."""
    data: dict[str, Mapping[str, Any]] = {
        name: result.data for name, result in signals.sources.items() if result.ok
    }
    out: dict[str, float] = {}
    for feature, (source, key) in _DIRECT.items():
        value = _number((data.get(source) or {}).get(key))
        if value is not None:
            out[feature] = value
    for feature, (source, key) in _LAST_YEAR.items():
        value = _last_year((data.get(source) or {}).get(key))
        if value is not None:
            out[feature] = value
    # Проверка не декоративная: имя признака, которого нет в списке домена, означает, что перевод
    # разошёлся со списком — и разошёлся бы молча, оставив вес висеть на пустом месте.
    unknown = sorted(set(out) - set(EXTERNAL_FEATURES))
    if unknown:
        raise ValueError(f"перевод признаков отдал неизвестные домену имена: {', '.join(unknown)}")
    return out


def _number(value: object) -> float | None:
    """Число или ``None``: ``None`` источника и нечисловое поле — одинаково «нет признака»."""
    if isinstance(value, bool):
        return float(value)
    if isinstance(value, int | float):
        return float(value)
    return None


def _last_year(series: object) -> float | None:
    """Значение ряда за последний год окна; пустой ряд — это отсутствие признака, а не ноль."""
    if not isinstance(series, Mapping) or not series:
        return None
    years = [key for key in series if str(key).isdigit()]
    if not years:
        return None
    return _number(series[max(years, key=lambda key: int(str(key)))])
