"""Пакетный сбор признаков: по пулу потоков на источник, кэш перед сетью, отказ — в поле.

Почему параллельность режется по источникам, а не по терминам. Лимиты стоят на источнике: GitHub
без токена — десять поисковых запросов в минуту на всех, arXiv просит три секунды между
обращениями, OpenAlex пускает в вежливый пул десять в секунду. Раздав потоки по терминам, мы
получили бы толпу, бьющуюся в один и тот же лимит, и ту же скорость, но с отказами. Поэтому у
каждого источника свой ограничитель темпа (:class:`~horizon_analytics.signals.http.Pace`) и свой
пул потоков: потоки нужны, чтобы перекрыть задержку сети, а темп остаётся общим и ровным.

Отсюда же деление на быстрые и медленные источники. OpenAlex, Википедия и Hacker News отвечают на
термин одним-тремя запросами, и две сотни кандидатов проходят за минуты — это годится для живого
анализа. GitHub без токена стоит минуту на термин и годится только для оффлайновой сборки
обучающей выборки; он помечен ``slow`` и отключается одним флагом.

Термин считается собранным, когда о нём высказались все источники — включая тех, кто отказал.
Отказ записывается в поле, а не роняет сбор: две сотни терминов не должны пропадать из-за 503.

Отсюда же два потолка времени. Сбор идёт внутри анализа, а анализ обязан закончиться: источник,
который отвечает по сорок пять секунд и трижды просит повторить, способен растянуть полторы сотни
терминов на часы, и снаружи это неотличимо от зависания. Поэтому у пакета есть общий бюджет, у
термина — свой, и по их исчерпании оставшиеся термины помечаются отказом «не успел», а не ждут.
Разницы между «источник промолчал» и «мы не дождались» для читателя отчёта нет: и то и другое —
признак, которого нет.

Ни один отказ источника не вправе унести с собой поток. Раньше мог: ``collect_term`` ловил четыре
вида исключений, а пятый — скажем, ``httpx.InvalidURL``, который в httpx не наследует
``HTTPError``, — убивал поток источника, и пакет вставал навсегда на ``ready.get()`` в ожидании
термина, о котором больше некому высказаться. Главный поток при этом спал в futex, соединений не
было ни одного, и процесс выглядел живым. Теперь поток источника ловит всё, а главный поток ждёт
с оглядкой на бюджет и на то, живы ли ещё работники.
"""

from __future__ import annotations

import logging
import queue
import threading
import time
from collections.abc import Callable, Iterable, Mapping, Sequence
from concurrent.futures import Future, ThreadPoolExecutor
from dataclasses import dataclass
from datetime import date
from pathlib import Path
from typing import Any

from horizon_analytics.signals.cache import DEFAULT_CACHE_DIR, SignalCache
from horizon_analytics.signals.http import Fetcher, Pace, SourceUnavailableError
from horizon_analytics.signals.models import SourceResult, TermSignals
from horizon_analytics.signals.sources import (
    arxiv,
    github,
    hackernews,
    openalex,
    patents,
    wikipedia,
)

__all__ = [
    "DEFAULT_YEARS",
    "FAST_SOURCES",
    "REQUESTS_PER_TERM",
    "SLOW_SOURCES",
    "SOURCE_NAMES",
    "TermRequest",
    "collect_signals",
    "collect_term",
]

_LOG = logging.getLogger(__name__)

#: Как часто главный поток просыпается, чтобы свериться с бюджетом и с живостью работников.
#: Четверть секунды не видна на фоне сетевых задержек и не даёт заснуть навсегда.
_POLL_SECONDS = 0.25

#: Окно наблюдения. 2018 — потому что раньше начинается предыстория почти любой нынешней темы и
#: ряд перестаёт различать «родилось» и «было всегда»; 2026 — текущий год, он неполон, и это
#: видно по самому ряду.
DEFAULT_YEARS: tuple[int, ...] = tuple(range(2018, 2027))

SOURCE_NAMES: tuple[str, ...] = (
    "openalex",
    "arxiv",
    "github",
    "hackernews",
    "wikipedia",
    "patents",
)

#: Источники, непригодные для живого анализа сотни кандидатов: их лимит считается в запросах
#: в минуту, а не в секунду.
SLOW_SOURCES: tuple[str, ...] = ("arxiv", "github")

#: Набор для живого анализа: одна-две секунды на термин при включённой параллельности.
FAST_SOURCES: tuple[str, ...] = tuple(name for name in SOURCE_NAMES if name not in SLOW_SOURCES)

#: Сколько запросов источник тратит на один термин при обычном (не вырожденном) термине.
#: Числа не декоративные: по ним считается ожидаемое время пакета и выбирается ширина пула.
REQUESTS_PER_TERM: dict[str, int] = {
    "openalex": 3,
    "arxiv": 9,
    "github": 10,
    "hackernews": 1,
    "wikipedia": 2,
    "patents": 0,
}

#: Ширина пула на источник. Больше единицы имеет смысл только там, где темп быстрее задержки
#: сети: у arXiv и GitHub шаг в секунды, и второй поток просто ждал бы в очереди за первым.
_DEFAULT_WORKERS: dict[str, int] = {
    "openalex": 4,
    "arxiv": 1,
    "github": 1,
    "hackernews": 4,
    "wikipedia": 4,
    "patents": 1,
}


@dataclass(frozen=True, slots=True)
class TermRequest:
    """Строка входа: название технологии и предметная область, к которой её отнесли."""

    term: str
    area: str | None = None

    @classmethod
    def from_json(cls, payload: Mapping[str, Any]) -> TermRequest:
        """Разбирает запись ``{"term": ..., "area": ...}``."""
        term = str(payload["term"]).strip()
        if not term:
            raise ValueError("пустой term во входном файле")
        area = payload.get("area")
        return cls(term=term, area=str(area) if area is not None else None)


@dataclass(frozen=True, slots=True)
class _SourceSpec:
    """Как ходить в один источник: имя, версия разбора, общий темп, ширина пула, сам запрос."""

    name: str
    version: int
    pace: Pace
    workers: int
    run: Callable[[Fetcher, str], Mapping[str, Any]]
    headers: Mapping[str, str] | None = None

    def make_fetcher(self) -> Fetcher:
        """Свой клиент на поток, общий ограничитель темпа на источник."""
        return Fetcher(pace=self.pace, headers=self.headers)


def _build_specs(
    *,
    years: Sequence[int],
    today: date,
    exhaustive_limit: int,
    workers: Mapping[str, int],
) -> dict[str, _SourceSpec]:
    """Собирает описания источников, зафиксировав в замыканиях параметры окна."""

    def width(name: str) -> int:
        return max(1, int(workers.get(name, _DEFAULT_WORKERS[name])))

    return {
        openalex.NAME: _SourceSpec(
            name=openalex.NAME,
            version=openalex.VERSION,
            pace=openalex.make_pace(),
            workers=width(openalex.NAME),
            run=lambda f, t: openalex.collect(f, t, years=years),
        ),
        arxiv.NAME: _SourceSpec(
            name=arxiv.NAME,
            version=arxiv.VERSION,
            pace=arxiv.make_pace(),
            workers=width(arxiv.NAME),
            run=lambda f, t: arxiv.collect(f, t, years=years),
        ),
        github.NAME: _SourceSpec(
            name=github.NAME,
            version=github.VERSION,
            pace=github.make_pace(),
            workers=width(github.NAME),
            headers=github.token_headers(),
            run=lambda f, t: github.collect(f, t, years=years, exhaustive_limit=exhaustive_limit),
        ),
        hackernews.NAME: _SourceSpec(
            name=hackernews.NAME,
            version=hackernews.VERSION,
            pace=hackernews.make_pace(),
            workers=width(hackernews.NAME),
            run=lambda f, t: hackernews.collect(f, t, years=years),
        ),
        wikipedia.NAME: _SourceSpec(
            name=wikipedia.NAME,
            version=wikipedia.VERSION,
            pace=wikipedia.make_pace(),
            workers=width(wikipedia.NAME),
            run=lambda f, t: wikipedia.collect(f, t, today=today),
        ),
        patents.NAME: _SourceSpec(
            name=patents.NAME,
            version=patents.VERSION,
            pace=patents.make_pace(),
            workers=width(patents.NAME),
            run=lambda f, t: patents.collect(f, t),
        ),
    }


def collect_signals(
    terms: Iterable[TermRequest],
    *,
    cache_dir: Path = DEFAULT_CACHE_DIR,
    years: Sequence[int] = DEFAULT_YEARS,
    today: date | None = None,
    sources: Sequence[str] = SOURCE_NAMES,
    workers: Mapping[str, int] | None = None,
    exhaustive_limit: int = github.DEFAULT_EXHAUSTIVE_LIMIT,
    cache_only: bool = False,
    budget_seconds: float | None = None,
    term_budget_seconds: float | None = None,
    on_term: Callable[[TermSignals], None] | None = None,
    on_source: Callable[[str, SourceResult, bool], None] | None = None,
) -> list[TermSignals]:
    """Собирает признаки для пакета терминов, отдавая каждый готовый термин в ``on_term``.

    Это и есть точка пакетного сбора: кэш один на весь пакет, параллельность ограничена
    источником, и двести терминов проходят за то же время, что самый медленный из включённых
    источников, а не за сумму по всем.

    Args:
        terms: Что собирать.
        cache_dir: Общий каталог кэша; повторный запрос той же фразы к тому же источнику не идёт
            в сеть — ни в этом запуске, ни в следующем.
        years: Окно наблюдения.
        today: Дата сбора; подменяется в тестах.
        sources: Какие источники опрашивать. ``FAST_SOURCES`` — набор для живого анализа.
        workers: Ширина пула на источник; по умолчанию :data:`_DEFAULT_WORKERS`.
        exhaustive_limit: До скольких репозиториев GitHub считается точным путём.
        cache_only: Не ходить в сеть вообще: собранное берётся из кэша, несобранное отмечается
            отказом «нет в кэше». Режим воспроизводимого перепрогона — та же выборка, те же
            числа, ноль запросов, — и единственный способ прогнать движок внешних признаков на
            эталонном корпусе, не завися от чужих серверов.
        budget_seconds: Потолок времени на весь пакет. ``None`` — без потолка, и это уместно
            только в оффлайновой сборке обучающей выборки, где ждать некому. Внутри анализа
            потолок обязателен: этап, который может не кончиться, однажды не кончится.
        term_budget_seconds: Потолок времени на один термин у одного источника. Нужен отдельно от
            общего: без него один повисший термин съедает бюджет всего пакета и остальные сто
            сорок девять остаются без признаков.
        on_term: Вызывается, как только термин собран целиком, — так CLI пишет по мере готовности.
        on_source: Вызывается после каждого источника: ``(термин, результат, из_кэша)``.

    Returns:
        Записи в том же порядке, в каком термины пришли на вход.
    """
    requests = list(terms)
    if not requests:
        return []
    day = today or date.today()
    built = _build_specs(
        years=years, today=day, exhaustive_limit=exhaustive_limit, workers=workers or {}
    )
    specs = [built[name] for name in sources]
    cache = SignalCache(cache_dir)
    deadline = (
        time.monotonic() + budget_seconds
        if budget_seconds is not None and budget_seconds > 0
        else None
    )

    lock = threading.Lock()
    gathered: dict[str, dict[str, SourceResult]] = {request.term: {} for request in requests}
    by_term = {request.term: request for request in requests}
    ready: queue.Queue[str] = queue.Queue()
    backlog: dict[str, queue.Queue[TermRequest]] = {}
    for spec in specs:
        pending: queue.Queue[TermRequest] = queue.Queue()
        for request in requests:
            pending.put(request)
        backlog[spec.name] = pending

    def attempt(spec: _SourceSpec, term: str, fetcher: Fetcher) -> SourceResult:
        """Спросить источник об одном термине так, чтобы наружу не вылетело ничего."""
        if deadline is not None and time.monotonic() >= deadline:
            return _unavailable(spec, day, "бюджет времени сбора исчерпан")
        try:
            return collect_term(
                spec,
                term,
                cache=cache,
                fetcher=fetcher,
                today=day,
                cache_only=cache_only,
                deadline=_term_deadline(deadline, term_budget_seconds),
            )
        # Ловится всё, и это не небрежность, а инвариант: поток источника, умерший на неожиданном
        # исключении, оставлял пакет ждать термина, о котором больше некому высказаться, — то
        # есть навсегда. Перечислить всё, чем способны упасть пять чужих API и их разборщики,
        # нельзя; можно гарантировать, что ответ будет у каждого термина.
        except Exception as error:
            _LOG.warning("%s/%s: неожиданный отказ разбора: %s", spec.name, term, error)
            return _unavailable(spec, day, f"{type(error).__name__}: {error}")

    def work(spec: _SourceSpec) -> None:
        fetcher = spec.make_fetcher()
        try:
            while True:
                try:
                    request = backlog[spec.name].get_nowait()
                except queue.Empty:
                    return
                result = attempt(spec, request.term, fetcher)
                with lock:
                    gathered[request.term][spec.name] = result
                    done = len(gathered[request.term]) == len(specs)
                if on_source is not None:
                    on_source(request.term, result, result.data.get("_cached", False) is True)
                if done:
                    ready.put(request.term)
        finally:
            fetcher.close()

    collected: dict[str, TermSignals] = {}

    def publish(term: str) -> None:
        """Собрать запись термина из того, что о нём известно, и отдать её наружу."""
        with lock:
            parts = dict(gathered[term])
        for spec in specs:
            # Источник, который не высказался, — это отказ «не успел», а не отсутствующий
            # источник: запись обязана быть одинаковой формы у всех терминов, иначе обучающая
            # таблица получит дыру там, где на самом деле стоял таймаут.
            parts.setdefault(spec.name, _unavailable(spec, day, "источник не успел ответить"))
        signals = TermSignals(
            term=term,
            area=by_term[term].area,
            collected_on=day,
            sources={name: _strip_marker(result) for name, result in sorted(parts.items())},
        )
        collected[term] = signals
        if on_term is not None:
            on_term(signals)

    pool_size = sum(spec.workers for spec in specs)
    pool = ThreadPoolExecutor(max_workers=pool_size, thread_name_prefix="signals")
    futures: list[Future[None]] = []
    try:
        futures = [pool.submit(work, spec) for spec in specs for _ in range(spec.workers)]
        for _ in requests:
            term = _next_ready(ready, futures, deadline)
            if term is None:
                # Бюджет кончился либо все работники завершились, не досчитав термины. И то и
                # другое — законный конец пакета: недостающие записи соберутся ниже, с отметкой
                # об отказе. Ждать дальше нечего и некого.
                break
            publish(term)
    finally:
        # Пул закрывается вручную, потому что ``with`` дожидается потоков всегда, а мы не вправе:
        # поток, застрявший внутри чужого разборщика, сделал бы бюджет этапа необязательным.
        # Отставший поток не бросается на произвол — он доигрывает текущий запрос (тот ограничен
        # сроком, выставленным на клиент) и завершается сам: на следующем термине он видит
        # исчерпанный бюджет и выходит. Записи к этому моменту уже собраны, и дописать он их не
        # может — `publish` читает `gathered` под замком.
        expired = deadline is not None and time.monotonic() >= deadline
        if expired:
            _LOG.warning(
                "бюджет сбора %.0f с исчерпан: собрано %d терминов из %d",
                budget_seconds or 0.0,
                len(collected),
                len(requests),
            )
        pool.shutdown(wait=not expired, cancel_futures=True)

    for request in requests:
        if request.term not in collected:
            publish(request.term)
    for future in futures:
        # Только у завершившихся: у работающего `exception()` ждёт, а ждать мы как раз перестали.
        error = future.exception() if future.done() else None
        if error is not None:
            # Поток источника ловит отказы сам, и сюда долетает только сломавшееся насмерть.
            # Раньше это поднималось наверх и роняло сбор; теперь записи уже собраны и отказ в
            # них отмечен, поэтому остаётся сказать об этом в журнал, а не отменять весь пакет.
            _LOG.error("поток источника завершился исключением: %s", error)

    return [collected[request.term] for request in requests]


def collect_term(
    spec: _SourceSpec,
    term: str,
    *,
    cache: SignalCache,
    fetcher: Fetcher,
    today: date,
    cache_only: bool = False,
    deadline: float | None = None,
) -> SourceResult:
    """Один источник про один термин: сначала кэш, потом сеть, отказ — в поле, а не наверх."""
    cached = cache.get(spec.name, term, spec.version)
    if cached is not None:
        _LOG.debug("%s/%s из кэша", spec.name, term)
        return SourceResult(
            source=cached.source,
            ok=cached.ok,
            collected_on=cached.collected_on,
            version=cached.version,
            data={**dict(cached.data), "_cached": True},
            error=cached.error,
        )
    if cache_only:
        # Промах кэша в этом режиме — такой же отказ источника, как 503: термин остаётся без
        # признаков, но пакет идёт дальше. Подменять промах нулями нельзя по той же причине, по
        # которой нельзя подменять ими отказ.
        return SourceResult(
            source=spec.name,
            ok=False,
            collected_on=today,
            version=spec.version,
            error="нет в кэше (режим cache_only)",
        )
    if fetcher is not None:
        # Срок ставится на клиент, а не передаётся в разборщик: у разборщика своя логика запросов
        # (у OpenAlex их три, у Википедии два), и знать о бюджете он не обязан.
        fetcher.set_deadline(deadline)
    try:
        data = spec.run(fetcher, term)
    except (SourceUnavailableError, ValueError, KeyError, TypeError) as exc:
        _LOG.warning("%s/%s: %s", spec.name, term, exc)
        return SourceResult(
            source=spec.name,
            ok=False,
            collected_on=today,
            version=spec.version,
            error=f"{type(exc).__name__}: {exc}",
        )
    result = SourceResult(
        source=spec.name, ok=True, collected_on=today, version=spec.version, data=dict(data)
    )
    cache.put(term, result)
    return result


def _strip_marker(result: SourceResult) -> SourceResult:
    """Убирает служебный признак «из кэша» — в выходной записи ему не место."""
    if "_cached" not in result.data:
        return result
    data = {key: value for key, value in result.data.items() if key != "_cached"}
    return SourceResult(
        source=result.source,
        ok=result.ok,
        collected_on=result.collected_on,
        version=result.version,
        data=data,
        error=result.error,
    )


def _term_deadline(deadline: float | None, term_budget_seconds: float | None) -> float | None:
    """Срок для одного термина: что раньше — его собственный потолок или общий бюджет."""
    limits: list[float] = []
    if term_budget_seconds is not None and term_budget_seconds > 0:
        limits.append(time.monotonic() + term_budget_seconds)
    if deadline is not None:
        limits.append(deadline)
    return min(limits) if limits else None


def _unavailable(spec: _SourceSpec, today: date, reason: str) -> SourceResult:
    """Отказ источника в форме результата — то, что кладётся в запись вместо чисел."""
    return SourceResult(
        source=spec.name, ok=False, collected_on=today, version=spec.version, error=reason
    )


def _next_ready(
    ready: queue.Queue[str], futures: Sequence[Future[None]], deadline: float | None
) -> str | None:
    """Дождаться следующего собранного термина.

    ``None`` означает «больше ждать нечего»: либо вышел бюджет, либо все потоки источников уже
    завершились. Раньше здесь стоял ``ready.get()`` без срока, и любой из этих двух случаев
    оставлял главный поток ждать вечно — при живом процессе, нулевой загрузке и без единого
    сетевого соединения.
    """
    while True:
        try:
            return ready.get(timeout=_POLL_SECONDS)
        except queue.Empty:
            if deadline is not None and time.monotonic() >= deadline:
                return None
            if all(future.done() for future in futures):
                # Гонка: работник мог положить термин в очередь ровно между двумя проверками.
                try:
                    return ready.get_nowait()
                except queue.Empty:
                    return None
