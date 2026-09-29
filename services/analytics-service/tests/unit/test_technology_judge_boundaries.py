"""Границы семантического судьи (ADR-0017): что он может и чего не может.

Проверка существует потому, что именно эти границы делают модель допустимой в продукте, где ТЗ
запрещает формировать выдачу «исключительно на основании знаний языковой модели». Границ три, и
каждая — отдельное утверждение, которое легко потерять при следующей правке конвейера:

1. судья способен только **убрать** пришедшее из поиска и не может ничего добавить;
2. он не влияет ни на балл, ни на порядок — проверка идёт после того, как ранжирование посчитано;
3. **молчание равно согласию**: отказ модели, её недоступность и ответ «не знаю» означают
   «оставить», а не «удалить». Без этого свойства сбой сети молча укорачивал бы отчёт.
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from datetime import date
from pathlib import Path
from types import SimpleNamespace

import pytest

from horizon_analytics.adapters.corpus.fixture_loader import load_documents
from horizon_analytics.adapters.embeddings.tfidf_svd import TfidfSvdEmbeddingProvider
from horizon_analytics.domain.models import AnalysisParams
from horizon_analytics.domain.pipeline import (
    JUDGE_LIMIT_FACTOR,
    AnalysisPipeline,
    PipelineRequest,
    TechnologyVerdict,
    _Tracer,
)
from horizon_analytics.domain.scoring.profile import MethodologyProfile


class _Judge:
    """Судья с заданным заранее ответом; записывает, о чём его спросили."""

    def __init__(self, verdicts: Mapping[str, TechnologyVerdict] | None = None) -> None:
        self._verdicts = dict(verdicts or {})
        self.asked: list[str] = []

    @property
    def model_id(self) -> str:
        return "test-judge"

    def judge(self, candidates: Sequence[str]) -> Mapping[str, TechnologyVerdict]:
        self.asked.extend(candidates)
        return self._verdicts


class _BrokenJudge:
    """Судья, который падает. Ровно то, что делает недоступный сервис моделей."""

    @property
    def model_id(self) -> str:
        return "broken-judge"

    def judge(self, candidates: Sequence[str]) -> Mapping[str, TechnologyVerdict]:
        raise RuntimeError("модель недоступна")


class _ContextJudge(_Judge):
    def __init__(self) -> None:
        super().__init__()
        self.contexts: dict[str, Sequence[str]] = {}

    def judge_with_evidence(
        self, candidates: Sequence[str], contexts: Mapping[str, Sequence[str]]
    ) -> Mapping[str, TechnologyVerdict]:
        self.contexts.update(contexts)
        return self.judge(candidates)


#: Мини-корпус, на котором уже стоят проверки исключения мейнстрима.
#:
#: Собственный синтетический корпус здесь не годится: при ровном ряде документов наклон
#: регрессии равен нулю и конвейер законно убирает все темы как зрелые, а при нулевой
#: цитируемости обнуляется индикатор влияния — проверка судьи превращалась бы в проверку
#: совсем других правил.
CORPUS = Path(__file__).resolve().parents[1] / "fixtures" / "mini_corpus.jsonl"


def _corpus():
    """Документы мини-корпуса; отсутствие файла — сломанный путь, а не отсутствующее окружение."""
    assert CORPUS.is_file(), f"мини-корпус не найден: {CORPUS}"
    return tuple(load_documents(CORPUS))


def _run(judge=None, top_n: int = 10):
    request = PipelineRequest(
        normalized_query="artificial intelligence",
        query="artificial intelligence",
        documents=_corpus(),
        params=AnalysisParams(top_n=top_n, years_window=7),
        profile=MethodologyProfile.default(),
        window_from=date(2019, 1, 1),
        window_to=date(2025, 12, 31),
        today=date(2026, 1, 1),
    )
    pipeline = AnalysisPipeline(
        embedding_provider=TfidfSvdEmbeddingProvider(),
        technology_judge=judge,
    )
    return pipeline.run(request)


@pytest.fixture(scope="module")
def without_judge():
    return _run(None)


def test_silence_keeps_every_topic(without_judge) -> None:
    """Судья, который ни о чём не высказался, не удаляет ни одной темы."""
    silent = _run(_Judge({}))

    assert [outcome.result.trend_key for outcome in silent.trends] == [
        outcome.result.trend_key for outcome in without_judge.trends
    ]


def test_judge_receives_source_context_for_the_name() -> None:
    judge = _ContextJudge()
    _run(judge)

    assert judge.asked
    assert any(judge.contexts.get(title) for title in judge.asked)
    assert all(len(fragment) <= 500 for rows in judge.contexts.values() for fragment in rows)


def test_unknown_verdict_keeps_the_topic(without_judge) -> None:
    """``is_technology=None`` означает «оставить»: модель не ответила, а не отвергла."""
    titles = [outcome.result.title for outcome in without_judge.trends]
    assert titles, "конвейер обязан вернуть хотя бы одну тему, иначе проверка пуста"
    judge = _Judge({titles[0]: TechnologyVerdict(is_technology=None, reason="не ответила")})

    kept = _run(judge)

    assert titles[0] in [outcome.result.title for outcome in kept.trends]


def test_a_broken_judge_does_not_break_the_analysis(without_judge) -> None:
    """Недоступный сервис моделей стоит отчёту фильтра, а не самого отчёта."""
    broken = _run(_BrokenJudge())

    assert [outcome.result.trend_key for outcome in broken.trends] == [
        outcome.result.trend_key for outcome in without_judge.trends
    ]


def test_a_negative_verdict_removes_exactly_that_topic(without_judge) -> None:
    """Отрицательный вердикт убирает одну тему и не трогает остальные."""
    titles = [outcome.result.title for outcome in without_judge.trends]
    assert len(titles) >= 2, "для проверки нужны хотя бы две темы"
    judge = _Judge({titles[0]: TechnologyVerdict(is_technology=False, reason="кусок фразы")})

    filtered = _run(judge)

    remaining = [outcome.result.title for outcome in filtered.trends]
    assert titles[0] not in remaining
    assert titles[1] in remaining


def test_the_judge_changes_no_score(without_judge) -> None:
    """Балл темы не зависит от того, работал судья или нет.

    Свойство, ради которого проверка стоит после ранжирования. Без него объяснение балла
    перестало бы быть проверяемым: одно и то же число зависело бы от ответа модели.
    """
    titles = [outcome.result.title for outcome in without_judge.trends]
    judge = _Judge({titles[0]: TechnologyVerdict(is_technology=False, reason="кусок фразы")})

    filtered = _run(judge)

    baseline = {o.result.trend_key: o.result.score for o in without_judge.trends}
    for outcome in filtered.trends:
        assert outcome.result.score == baseline[outcome.result.trend_key]


def test_the_judge_cannot_add_a_topic(without_judge) -> None:
    """Положительный вердикт о строке, которой в выдаче не было, ничего не добавляет."""
    judge = _Judge(
        {"технология, которой нет в корпусе": TechnologyVerdict(is_technology=True, reason="да")}
    )

    widened = _run(judge)

    assert len(widened.trends) == len(without_judge.trends)


def test_only_the_head_is_judged(without_judge) -> None:
    """Судью спрашивают только о верхушке: вывод модели стоит времени показа."""
    judge = _Judge({})

    _run(judge)

    assert len(judge.asked) <= 10 * 2


def test_a_promoted_topic_does_not_reach_the_report_unjudged() -> None:
    """Место выбывшей темы занимает прочитанная строка, а не первая непрочитанная.

    Окно фиксированной глубины давало обратное. Замер на живой выдаче 2026-09-18: судья убрал
    двенадцать строк из сорока пяти, освободившиеся места заняли строки с сорок шестой — и в
    ТОП-15 попали непрочитанными «map», «candidate», «extensive», «fixed». Тот же судья,
    спрошенный о них отдельно, отвечает «общее слово» и «свойство».

    Проверяется сама стадия, а не весь конвейер: мини-корпус даёт шесть тем, и на шести темах
    подъём из-за окна не воспроизводится — для него нужно кандидатов больше, чем глубина окна.
    """
    survivors = [
        (SimpleNamespace(title=f"строка {index}", trend_key=f"строка {index}"), None, ())
        for index in range(1, 61)
    ]
    request = SimpleNamespace(params=AnalysisParams(top_n=5, years_window=7))
    # Первое окно при ТОП-5 — пятнадцать строк; отвергаются все.
    judge = _Judge(
        {
            f"строка {index}": TechnologyVerdict(is_technology=False, reason="общее слово")
            for index in range(1, 16)
        }
    )
    drops: dict[str, int] = {}

    kept = AnalysisPipeline._drop_non_technologies(
        survivors, request, drops, _Tracer(watch=frozenset()), judge
    )

    titles = [result.title for result, _, _ in kept[:5]]
    assert titles, "отчёт остался пустым: подменять состав стадия не должна"
    assert set(titles) <= set(judge.asked), "в отчёт попала строка, о которой судью не спросили"
    assert drops["not_technology"] == 15


def test_the_judge_is_not_asked_without_end(monkeypatch) -> None:
    """Корпус, в котором судья отвергает всё, не уводит анализ в модель навсегда.

    Глубину сверх ``JUDGE_LIMIT_FACTOR`` ограничивает бюджет времени; с исчерпанным бюджетом
    судья читает ровно прежний потолок.
    """
    from horizon_analytics.domain import pipeline as module

    monkeypatch.setattr(module, "HEAD_SCREEN_BUDGET_SECONDS", 0.0)
    survivors = [
        (SimpleNamespace(title=f"строка {index}", trend_key=f"строка {index}"), None, ())
        for index in range(1, 201)
    ]
    request = SimpleNamespace(params=AnalysisParams(top_n=5, years_window=7))
    judge = _Judge(
        {
            f"строка {index}": TechnologyVerdict(is_technology=False, reason="общее слово")
            for index in range(1, 201)
        }
    )

    AnalysisPipeline._drop_non_technologies(
        survivors, request, {}, _Tracer(watch=frozenset()), judge
    )

    # Плюс порции, прочитанные наперёд (JUDGE_PREFETCH × две ТОП-N): их ответ просто не нужен.
    assert len(judge.asked) <= 5 * JUDGE_LIMIT_FACTOR + 2 * 5 * 2
