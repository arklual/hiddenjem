"""Внешняя проверка зрелости: что она может и чего не может.

Стадия существует потому, что корпус видит только себя. Правило мейнстрима движка меряет долю
темы в литературе, собранной под этот запрос, и не знает, что по «federated learning» за три
года вышли десятки тысяч работ. Этап 1 (замер на размеченном датасете) судит именно по внешним
следам, и продукт на открытом запросе обязан судить так же.

Границы у стадии те же, что у семантического судьи (ADR-0017), и проверяются они здесь:

1. она только **убирает** — добавить тему не может;
2. **молчание — оставить**: источник не ответил, бюджет кончился, аббревиатура неоднозначна;
3. место выбывшей темы занимает **проверенная**, а не первая непроверенная (разбор 87).
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from datetime import date
from types import SimpleNamespace

from horizon_analytics.adapters.maturity.live import LiveMaturityProbe
from horizon_analytics.domain.models import AnalysisParams
from horizon_analytics.domain.pipeline import (
    AnalysisPipeline,
    MaturityVerdict,
    _Tracer,
)
from horizon_analytics.validation.sources import SourceEvidence


class _Probe:
    """Проверка с заданными заранее ответами; записывает, о чём её спросили."""

    def __init__(self, mature: Mapping[str, str]) -> None:
        self._mature = dict(mature)
        self.asked: list[str] = []

    @property
    def source_id(self) -> str:
        return "test"

    def probe(self, titles: Sequence[str]) -> Mapping[str, object]:
        self.asked.extend(titles)
        return {
            title: MaturityVerdict(mature=True, reason=self._mature[title])
            for title in titles
            if title in self._mature
        }


class _BrokenProbe:
    @property
    def source_id(self) -> str:
        return "broken"

    def probe(self, titles: Sequence[str]) -> Mapping[str, object]:
        raise RuntimeError("OpenAlex недоступен")


def _survivors(count: int) -> list[tuple[SimpleNamespace, None, tuple[()]]]:
    return [
        (SimpleNamespace(title=f"тема {index}", trend_key=f"тема {index}"), None, ())
        for index in range(1, count + 1)
    ]


def _request(top_n: int = 5) -> SimpleNamespace:
    return SimpleNamespace(params=AnalysisParams(top_n=top_n, years_window=7))


def _run(
    probe: object, survivors: list[tuple[SimpleNamespace, None, tuple[()]]]
) -> tuple[list[str], dict[str, int], _Tracer]:
    drops: dict[str, int] = {}
    tracer = _Tracer(watch=frozenset())
    kept = AnalysisPipeline._drop_externally_mature(
        survivors, _request(), drops, tracer, probe  # type: ignore[arg-type]
    )
    return [row[0].title for row in kept], drops, tracer


def test_a_mature_topic_leaves_with_a_reason() -> None:
    probe = _Probe({"тема 2": "о технологии вышло 46019 научных работ за три года"})

    titles, drops, _ = _run(probe, _survivors(10))

    assert "тема 2" not in titles
    assert drops["mainstream_external"] == 1


def test_silence_keeps_every_topic() -> None:
    """Проверка, которая ни о чём не высказалась, не удаляет ни одной темы."""
    titles, drops, _ = _run(_Probe({}), _survivors(10))

    assert titles == [f"тема {index}" for index in range(1, 11)]
    assert "mainstream_external" not in drops


def test_a_broken_source_does_not_break_the_analysis() -> None:
    titles, drops, _ = _run(_BrokenProbe(), _survivors(10))

    assert titles == [f"тема {index}" for index in range(1, 11)]
    assert drops["maturity_unavailable"] == 1


def test_a_promoted_topic_is_checked_before_it_reaches_the_report() -> None:
    """Всё первое окно (ТОП-5 × 2 = 10) массовое — следующие темы тоже проверяются."""
    probe = _Probe({f"тема {index}": "статья в Википедии с 2012 года" for index in range(1, 11)})

    titles, _, _ = _run(probe, _survivors(40))

    assert titles[:5], "отчёт остался пустым"
    assert set(titles[:5]) <= set(probe.asked), "в отчёт попала непроверенная тема"


def test_the_probe_cannot_add_a_topic() -> None:
    probe = _Probe({"технология, которой нет в выдаче": "что угодно"})

    titles, _, _ = _run(probe, _survivors(10))

    assert len(titles) == 10


# ── адаптер ──────────────────────────────────────────────────────────────────


def _evidence(
    *, works: Mapping[int, int] | None = None, wikipedia: tuple[int, int] | None = None
) -> SourceEvidence:
    evidence = SourceEvidence(query="x", effective_query="x")
    evidence.works_by_year = dict(works or {})
    if wikipedia is not None:
        evidence.wikipedia["en"] = wikipedia
    return evidence


def _probe(collect: object) -> LiveMaturityProbe:
    return LiveMaturityProbe(
        contact_email="ops@horizon.example",
        today=lambda: date(2026, 9, 18),
        collect=collect,  # type: ignore[arg-type]
    )


def test_the_adapter_uses_the_validated_rule() -> None:
    """Тысячи работ за три года — массовая; порог тот же, что в замере на датасете."""
    probe = _probe(lambda phrase: _evidence(works={2024: 20000, 2025: 26000}))

    verdict = probe.probe(["federated learning"])["federated learning"]

    assert isinstance(verdict, MaturityVerdict)
    assert verdict.mature is True
    assert "научных работ" in verdict.reason


def test_a_short_acronym_is_not_judged_by_work_count() -> None:
    """`MCP` находит и Model Context Protocol, и десяток других MCP: число меряет омонимию."""
    probe = _probe(lambda phrase: _evidence(works={2024: 20000, 2025: 26000}))

    verdict = probe.probe(["MCP"])["MCP"]

    assert isinstance(verdict, MaturityVerdict)
    assert verdict.mature is False


def test_an_unreachable_source_is_silence() -> None:
    def collect(phrase: str) -> SourceEvidence:
        evidence = _evidence()
        evidence.unavailable = ("openalex", "wikipedia.en")
        return evidence

    assert _probe(collect).probe(["speculative decoding"]) == {}


def test_an_exhausted_budget_leaves_the_rest_unchecked() -> None:
    asked: list[str] = []

    def collect(phrase: str) -> SourceEvidence:
        asked.append(phrase)
        return _evidence(works={2025: 50000})

    probe = LiveMaturityProbe(
        contact_email="ops@horizon.example", budget_seconds=0.0, collect=collect
    )

    assert probe.probe(["a", "b", "c"]) == {}
    assert asked == []


class _Judge:
    """Судья, отвергающий строки из заданного набора; записывает, о чём его спросили."""

    def __init__(self, junk: set[str]) -> None:
        self._junk = junk
        self.asked: list[str] = []

    @property
    def model_id(self) -> str:
        return "test-judge"

    def judge(self, candidates: Sequence[str]) -> Mapping[str, object]:
        from horizon_analytics.domain.pipeline import TechnologyVerdict

        self.asked.extend(candidates)
        return {
            title: TechnologyVerdict(is_technology=False, reason="общее слово")
            for title in candidates
            if title in self._junk
        }


def test_a_topic_in_the_report_has_passed_both_checks() -> None:
    """Судья и проверка зрелости — одна петля: подъём непрочитанных между стадиями невозможен.

    Воспроизводит прогон по ИИ 2026-09-18. Судья одобряет первые пятнадцать, проверка зрелости
    признаёт их все массовыми. Двумя стадиями подряд судья к этому моменту уже остановился, и
    на освободившиеся места поднимались строки, которых он не читал, — «model alone», «four model
    families». Одной петлёй каждая следующая порция снова проходит через судью.
    """
    survivors = _survivors(60)
    junk = {f"тема {index}" for index in range(16, 60, 2)}  # мусор дальше по списку
    judge = _Judge(junk)
    probe = _Probe({f"тема {index}": "46 000 работ за три года" for index in range(1, 16)})
    drops: dict[str, int] = {}

    kept = AnalysisPipeline._screen_head(
        survivors,  # type: ignore[arg-type]
        _request(top_n=5),  # type: ignore[arg-type]
        drops,
        _Tracer(watch=frozenset()),
        judge=judge,
        maturity=probe,
    )

    report = [row[0].title for row in kept[:5]]
    assert report, "отчёт остался пустым"
    assert set(report) <= set(judge.asked), "в отчёт попала строка, которую судья не читал"
    assert set(report) <= set(probe.asked), "в отчёт попала строка без проверки зрелости"
    assert not set(report) & junk, "мусор поднялся на место массовой темы"


def test_a_failed_check_does_not_silence_the_other() -> None:
    """Отказ внешнего источника не отключает судью: мусор по-прежнему убирается."""
    judge = _Judge({"тема 1", "тема 2"})
    drops: dict[str, int] = {}

    kept = AnalysisPipeline._screen_head(
        _survivors(20),  # type: ignore[arg-type]
        _request(top_n=5),  # type: ignore[arg-type]
        drops,
        _Tracer(watch=frozenset()),
        judge=judge,
        maturity=_BrokenProbe(),
    )

    titles = [row[0].title for row in kept]
    assert "тема 1" not in titles and "тема 2" not in titles
    assert drops["maturity_unavailable"] == 1


def test_an_exhausted_depth_does_not_pad_the_report_with_unread_rows() -> None:
    """Потолок глубины при исправных проверках — не «молчание»: хвост не дописывается.

    Прогон по ИИ 2026-09-18 дописывал непрочитанный хвост и получил на места 10–15 «blocks»,
    «five», «length», «index». Отказ проверки — другое дело: там непрочитанное остаётся.
    """
    judge = _Judge({f"тема {index}" for index in range(1, 201)})  # отвергает всё
    drops: dict[str, int] = {}

    kept = AnalysisPipeline._screen_head(
        _survivors(200),  # type: ignore[arg-type]
        _request(top_n=5),  # type: ignore[arg-type]
        drops,
        _Tracer(watch=frozenset()),
        judge=judge,
        maturity=None,
    )

    assert kept == [], "в отчёт попали строки, которых никто не проверял"
    # Судья дочитал очередь целиком: глубину решает бюджет времени, а не потолок по числу строк.
    assert len(judge.asked) == 200


def test_the_report_is_filled_with_technologies_rejected_only_as_mainstream() -> None:
    """Условие задачи — не меньше ТОП-N тем. Не набралось проверенных — добираем технологиями,
    которые судья признал, а отсеяла лишь проверка массовости; мусор судьи не добирается никогда.
    """
    survivors = _survivors(12)
    junk = {f"тема {index}" for index in range(1, 13) if index % 3 == 0}  # 3, 6, 9, 12
    mature = {f"тема {index}": "массовая" for index in (1, 2, 4, 5, 7, 8)}
    drops: dict[str, int] = {}

    kept = AnalysisPipeline._screen_head(
        survivors,  # type: ignore[arg-type]
        _request(top_n=5),  # type: ignore[arg-type]
        drops,
        _Tracer(watch=frozenset()),
        judge=_Judge(junk),
        maturity=_Probe(mature),
    )

    titles = [row[0].title for row in kept]
    # Прошли обе проверки только 10 и 11; три места добраны признанными технологиями в порядке
    # балла, и порядок очереди сохранён.
    assert titles == ["тема 1", "тема 2", "тема 4", "тема 10", "тема 11"]
    assert not (set(titles) & junk), "в отчёт добрана строка, которую судья отверг"
    assert drops["filled_from_mainstream"] == 3


def test_an_expanded_title_is_checked_by_its_long_form() -> None:
    """«speculative decoding (SD)» ищется как «speculative decoding», а не со скобкой."""
    asked: list[str] = []

    def collect(phrase: str) -> SourceEvidence:
        asked.append(phrase)
        return _evidence(works={2025: 100})

    _probe(collect).probe(["speculative decoding (SD)"])

    assert asked == ["speculative decoding"]


def test_without_a_key_only_wikipedia_is_asked() -> None:
    """Без ключа OpenAlex не спрашивается: суточный бюджет нужен самому сбору (разбор 88)."""
    probe = LiveMaturityProbe(contact_email="ops@horizon.example", collect=lambda p: _evidence())

    assert probe.source_id == "wikipedia.en"


def test_the_same_phrase_is_not_paid_for_twice() -> None:
    """Названия повторяются от анализа к анализу, а каждый вопрос OpenAlex стоит бюджета."""
    asked: list[str] = []

    def collect(phrase: str) -> SourceEvidence:
        asked.append(phrase)
        return _evidence(wikipedia=(2019, 58000))

    probe = _probe(collect)
    probe.probe(["federated learning"])
    probe.probe(["federated learning"])

    assert asked == ["federated learning"]
