"""BRULE-3: тема, уже ставшая мейнстримом направления, в отчёт не попадает.

Правило, которое делает продукт продуктом о **зарождающемся**, а не о популярном. Без него отчёт
вырождается в «самые публикуемые темы» — ровно ту наивную базу сравнения, которую бэктест
существует, чтобы обыгрывать (§15). Ошибка не выглядит ошибкой: список остаётся полным,
убедительным и бесполезным, потому что аналитик и так знает, о чём все пишут.

Подмены по всему набору, включая эталонный корпус, показали, что правило не держал ни один тест:
исключение мейнстрима можно было снять целиком, а просьбу «покажи и зрелые» — игнорировать.

Одна охрана правила проверками не покрыта, и это отмечено сознательно: условие
``mainstream_threshold > 0`` защищает вырожденный корпус, а на любом непустом корпусе порог —
квантиль объёмов тем, то есть не меньше единицы. Подмена этой охраны эквивалентна оригиналу на
всяком достижимом входе, и тест, «покрывающий» её, проверял бы не поведение, а собственное
существование.

Проверяется на мини-корпусе, собранном ровно для этого: `convolutional neural network` там сделан
«старым и громким» специально ради BRULE-3 (см. `tests/fixtures/build_mini_corpus.py`). Фикстура
существовала, правило — тоже, а связи между ними не было.
"""

from __future__ import annotations

from datetime import date
from pathlib import Path

import pytest

from horizon_analytics.adapters.corpus.fixture_loader import load_documents
from horizon_analytics.adapters.embeddings.tfidf_svd import TfidfSvdEmbeddingProvider
from horizon_analytics.domain.extraction.blacklist import stem_phrase
from horizon_analytics.domain.models import AnalysisParams, Document
from horizon_analytics.domain.pipeline import AnalysisPipeline, PipelineRequest
from horizon_analytics.domain.scoring.profile import MethodologyProfile

CORPUS = Path(__file__).resolve().parents[1] / "fixtures" / "mini_corpus.jsonl"

#: Тема, которую фикстура делает мейнстримом направления: старая, громкая, во всех источниках.
MAINSTREAM = "convolutional neural network"


@pytest.fixture(scope="module")
def corpus() -> tuple[Document, ...]:
    # Падение, а не пропуск: фикстура лежит в репозитории, и её отсутствие означает сломанный путь,
    # а не отсутствующее окружение. Пропущенная проверка выглядит в отчёте как пройденная.
    assert CORPUS.is_file(), f"мини-корпус не найден: {CORPUS}"
    return tuple(load_documents(CORPUS))


def run(corpus: tuple[Document, ...], *, include_mature: bool):
    """Прогон с трассировкой мейнстримной темы: нас интересует, где именно она выбывает."""
    request = PipelineRequest(
        normalized_query="artificial intelligence",
        documents=corpus,
        params=AnalysisParams(top_n=15, years_window=8, include_mature=include_mature),
        profile=MethodologyProfile.default(),
        window_from=date(2015, 1, 1),
        window_to=date(2025, 12, 31),
        today=date(2026, 1, 1),
        query="artificial intelligence",
        watch=frozenset({stem_phrase(MAINSTREAM)}),
    )
    return AnalysisPipeline(embedding_provider=TfidfSvdEmbeddingProvider()).run(request)


def exit_stage(result, needle: str = "convolut") -> str | None:
    """На какой стадии выбыла отслеживаемая тема."""
    for trace in result.traces:
        if needle in trace.key:
            return trace.stage
    return None


class TestMainstreamExclusion:
    def test_a_mainstream_topic_leaves_at_the_mainstream_rule(self, corpus) -> None:
        result = run(corpus, include_mature=False)

        assert (
            exit_stage(result) == "mainstream"
        ), f"«{MAINSTREAM}» — мейнстрим направления, и выбыть она должна именно по BRULE-3"
        assert dict(result.diagnostics).get("brule3_mainstream", 0) > 0
        assert not any("convolut" in outcome.result.trend_key for outcome in result.trends)

    def test_asking_for_mature_topics_switches_the_rule_off(self, corpus) -> None:
        # Обратная граница, и она важнее первой: без неё правило можно «выполнить», выбрасывая тему
        # всегда, — и параметр `includeMature` молча перестал бы что-либо значить.
        #
        # Проверяется отключение правила, а не публикация темы. Публикации не будет и при
        # включённых зрелых: у этой темы нулевой рост, и её убирает BRULE-4 — что трассировка и
        # сообщает. Требовать здесь публикации значило бы требовать от одного правила отменять
        # другое.
        result = run(corpus, include_mature=True)

        assert exit_stage(result) != "mainstream", "просьбу показать зрелые темы продукт не услышал"
        assert dict(result.diagnostics).get("brule3_mainstream", 0) == 0

    def test_the_switch_moves_only_the_maturity_rules(self, corpus) -> None:
        # Переключатель обязан менять ровно то, что обещает, — и ничего кроме. Иначе он влияет на
        # отбор шире, чем написано, и два прогона нельзя сравнивать между собой.
        #
        # Правил зрелости стало два, и это не расширение переключателя, а исправление отбора.
        # BRULE-3 спрашивает «занимает ли тема заметную долю литературы направления» и зрелую тему
        # небольшого объёма пропускает; живой прогон по направлению ИИ дал три темы со стадией
        # MATURING в ТОП-15, то есть отчёт о слабых сигналах на пятую часть состоял из зрелого —
        # прямо против ТЗ. Поэтому под переключателем теперь оба правила, и проверка требует
        # именно этого: всё, что появляется при «покажи и зрелые», зрело по одному из них.
        without = run(corpus, include_mature=False)
        with_mature = run(corpus, include_mature=True)
        published = {o.result.trend_key for o in without.trends}
        appeared = [o for o in with_mature.trends if o.result.trend_key not in published]

        assert not {o.result.trend_key for o in without.trends} - {
            o.result.trend_key for o in with_mature.trends
        }, "переключатель зрелости не должен убирать темы из выдачи"
        for outcome in appeared:
            assert outcome.result.lifecycle_stage == "MATURING", (
                f"«{outcome.result.trend_key}» появилась при includeMature, "
                f"но её стадия — {outcome.result.lifecycle_stage}: переключатель двигает "
                "не только правила зрелости"
            )
