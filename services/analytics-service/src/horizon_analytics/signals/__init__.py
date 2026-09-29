"""Измеримые признаки жизненного цикла технологии из открытых источников.

По английской фразе («speculative decoding») библиотека собирает то, что об этой технологии
видно снаружи: сколько про неё пишут учёные (OpenAlex), сколько кладут препринтов (arXiv), сколько
заводят репозиториев и звёзд (GitHub), сколько о ней говорят инженеры (Hacker News) и добралась ли
она до энциклопедии (Википедия). Патентный источник исследован и оказался недоступен без ключа —
см. :mod:`horizon_analytics.signals.sources.patents`.

Три правила, из которых выросла вся остальная конструкция:

* **никаких выдуманных чисел** — источник, который не ответил, оставляет пустое поле с причиной,
  а не ноль;
* **кэш на диске** — сбор ста терминов идёт больше часа, и он должен быть воспроизводим и
  докачиваем, а не переигрываться с нуля после каждой правки;
* **свой темп на источник** — лимиты стоят на источниках, поэтому и параллельность делится по
  ним, а не по терминам.

Источники делятся на быстрые и медленные, и это не про вкус, а про пригодность. OpenAlex,
Википедия и Hacker News тратят на термин один-три запроса, поэтому двести кандидатов проходят за
минуты и годятся для живого анализа (:data:`FAST_SOURCES`). arXiv и GitHub считают лимит в
запросах в минуту, стоят почти минуту на термин и годятся только для оффлайновой сборки обучающей
выборки (:data:`SLOW_SOURCES`).

    from horizon_analytics.signals import FAST_SOURCES, TermRequest, collect_signals

    signals = collect_signals(
        [TermRequest("speculative decoding", area="ai")], sources=FAST_SOURCES
    )
    print(signals[0].features()["openalex.works_window_total"])
"""

from horizon_analytics.signals.cache import DEFAULT_CACHE_DIR, SignalCache
from horizon_analytics.signals.collector import (
    DEFAULT_YEARS,
    FAST_SOURCES,
    REQUESTS_PER_TERM,
    SLOW_SOURCES,
    SOURCE_NAMES,
    TermRequest,
    collect_signals,
)
from horizon_analytics.signals.models import SourceResult, TermSignals

__all__ = [
    "DEFAULT_CACHE_DIR",
    "DEFAULT_YEARS",
    "FAST_SOURCES",
    "REQUESTS_PER_TERM",
    "SLOW_SOURCES",
    "SOURCE_NAMES",
    "SignalCache",
    "SourceResult",
    "TermRequest",
    "TermSignals",
    "collect_signals",
]
