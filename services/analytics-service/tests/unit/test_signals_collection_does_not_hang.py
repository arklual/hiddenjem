"""Сбор признаков обязан кончиться. Раньше не был обязан.

Что случилось на стенде. Движок `signals` с живым опросом встал после загрузки корпуса: процесс
жив двадцать пять минут, загрузка 0,3 %, шестнадцать потоков, главный спит в futex, исходящих
соединений нет ни одного. Тот же прогон с `cache_only=true` проходил за секунды — и это была
подсказка: в режиме кэша `collect_term` возвращается **до** обращения к разборщику источника, то
есть чужой код вообще не исполняется.

Причина. `collect_term` ловил четыре вида исключений, а разборщик мог бросить пятый — например,
`httpx.InvalidURL`, который в httpx не наследует `HTTPError` и потому проходил сквозь сетевой
слой. Исключение убивало поток источника, а главный поток ждал на `ready.get()` термина, о
котором больше некому было высказаться. Ждал без срока, то есть всегда.

Здесь это закреплено тремя свойствами, каждое из которых на прежнем коде не выполнялось:

* неожиданное исключение разборщика не уносит с собой пакет;
* бюджет времени — потолок, а не пожелание: этап кончается, даже когда источник не отвечает;
* термин, до которого не дошли, получает запись с отметкой об отказе, а не исчезает.

Источники здесь поддельные и нарочно медленные — это проверка сборщика, а не источников. Сеть не
участвует: подменяются разборщики, то есть та самая чужая половина, на которой всё и ломалось.
"""

from __future__ import annotations

import time
from datetime import date
from pathlib import Path
from typing import Any

import httpx
import pytest

from horizon_analytics.signals.collector import (
    TermRequest,
    collect_signals,
)
from horizon_analytics.signals.http import Fetcher, Pace, SourceUnavailableError
from horizon_analytics.signals.sources import openalex, wikipedia

TODAY = date(2026, 9, 20)

#: Быстрый источник без сети — берётся третьим, чтобы в пакете был и тот, кто отвечает.
QUIET = "patents"


def terms(count: int) -> list[TermRequest]:
    return [TermRequest(f"term-{index}") for index in range(count)]


def explodes(error: Exception):
    """Разборщик, который падает так, как сборщик не ждёт."""

    def run(fetcher: Any, term: str, **kwargs: Any) -> dict[str, Any]:
        raise error

    return run


def sleeps(seconds: float):
    """Разборщик, который не отвечает: чужой сервер, повисший на чтении."""

    def run(fetcher: Any, term: str, **kwargs: Any) -> dict[str, Any]:
        time.sleep(seconds)
        return {"exists": False}

    return run


@pytest.fixture(autouse=True)
def offline(monkeypatch: pytest.MonkeyPatch) -> None:
    """Ни один разборщик этого файла не ходит в сеть: их подменяют в каждой проверке."""
    monkeypatch.setattr(openalex, "collect", explodes(AssertionError("источник не спрошен")))
    monkeypatch.setattr(wikipedia, "collect", explodes(AssertionError("источник не спрошен")))


def test_an_unexpected_parser_failure_does_not_take_the_batch_with_it(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    # Ровно тот отказ, который вешал стенд: httpx.InvalidURL мимо HTTPError, мимо сетевого слоя,
    # мимо collect_term — и поток источника умирал, унося с собой весь пакет.
    monkeypatch.setattr(openalex, "collect", explodes(httpx.InvalidURL("кривой адрес")))
    started = time.monotonic()

    collected = collect_signals(
        terms(3),
        cache_dir=tmp_path,
        sources=("openalex", QUIET),
        today=TODAY,
        budget_seconds=20.0,
        term_budget_seconds=5.0,
    )

    assert time.monotonic() - started < 10.0, "сбор завис на отказе разборщика"
    assert len(collected) == 3
    for item in collected:
        assert item.failures == ("openalex",)
        assert "InvalidURL" in (item.sources["openalex"].error or "")
        # Источник, который ответил, не пострадал от соседа.
        assert item.sources[QUIET].ok


def test_a_source_that_never_answers_does_not_outlive_the_budget(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    # Потолок времени — потолок, а не пожелание: молчащий источник не вправе задержать анализ
    # дольше отпущенного, чем бы он ни был занят.
    monkeypatch.setattr(wikipedia, "collect", sleeps(30.0))
    started = time.monotonic()

    collected = collect_signals(
        terms(3),
        cache_dir=tmp_path,
        sources=("wikipedia", QUIET),
        today=TODAY,
        budget_seconds=2.0,
        term_budget_seconds=1.0,
    )
    elapsed = time.monotonic() - started

    assert elapsed < 10.0, f"сбор шёл {elapsed:.1f} с при бюджете 2 с"
    # Запись есть у каждого запрошенного термина: «не успели» — это наблюдение, и оно
    # записывается, а не теряется вместе с термином.
    assert [item.term for item in collected] == [request.term for request in terms(3)]
    assert all("wikipedia" in item.failures for item in collected)


def test_a_cached_term_is_returned_even_when_its_neighbour_hangs(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    # Состояние стенда: часть терминов в кэше, часть нет. Собранное обязано вернуться собранным,
    # а не пропасть вместе с тем, до кого не дошла очередь.
    monkeypatch.setattr(wikipedia, "collect", lambda f, t, **k: {"exists": True, "size_bytes": 10})
    collect_signals(
        [TermRequest("term-0")],
        cache_dir=tmp_path,
        sources=("wikipedia",),
        today=TODAY,
        budget_seconds=5.0,
    )

    monkeypatch.setattr(wikipedia, "collect", sleeps(30.0))
    collected = collect_signals(
        terms(2),
        cache_dir=tmp_path,
        sources=("wikipedia",),
        today=TODAY,
        budget_seconds=2.0,
        term_budget_seconds=1.0,
    )

    by_term = {item.term: item for item in collected}
    assert by_term["term-0"].sources["wikipedia"].ok, "кэш не спас уже собранный термин"
    assert by_term["term-0"].features()["wikipedia.size_bytes"] == 10.0
    assert by_term["term-1"].failures == ("wikipedia",)


def test_the_pace_does_not_sleep_past_the_deadline() -> None:
    # Ожидание темпа само по себе неограниченно: при исчерпанной квоте `defer` отодвигает
    # очередь на пять минут, и запрос, который всё равно не состоится, держал бы бюджет.
    pace = Pace(0.0)
    pace.defer(60.0)
    started = time.monotonic()

    allowed = pace.wait(deadline=time.monotonic() + 0.2)

    assert allowed is False
    assert time.monotonic() - started < 1.0


def test_a_fetcher_past_its_deadline_refuses_instead_of_waiting() -> None:
    # Клиент с истёкшим сроком обязан отказать сразу: сорок пять секунд чтения из молчащего
    # сокета переживают любой потолок этапа.
    fetcher = Fetcher(pace=Pace(0.0), client=httpx.Client(transport=httpx.MockTransport(_never)))
    fetcher.set_deadline(time.monotonic() - 1.0)

    with pytest.raises(SourceUnavailableError, match="время источника вышло"):
        fetcher.get("https://example.org/works")


def _never(request: httpx.Request) -> httpx.Response:
    raise AssertionError("клиент с истёкшим сроком не должен делать запрос")
