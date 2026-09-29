"""Один и тот же запрос обязан давать один и тот же отчёт (ADR-0015).

Это центральное обещание продукта, а не гигиена: отчёт уходит в комитет, и вопрос «почему в
прошлый раз было иначе» не имеет приемлемого ответа. `build_domain_analyzed` прямо ссылается на
«golden-тест, который это проверяет» — каталога с ним не существовало, а `make reproduce` указывал
на пустоту и всегда падал ошибкой pytest, а не отказом проверки.

Сквозной сценарий проверяет то же свойство через весь стек, но по двум обращениям к API: он не
отличит настоящую воспроизводимость от кеша готового отчёта. Здесь конвейер запускается дважды
по-настоящему.

Русское направление проверяется наравне с английским намеренно: перекрёстный словарь направлений
(`docs/01-analysis/28-direction-lexicon-spec.md`) работает через множества, а множество — обычный
источник зависимости от порядка.
"""

from __future__ import annotations

import json
from datetime import date
from pathlib import Path

import pytest

from horizon_analytics.adapters.corpus.fixture_loader import load_documents
from horizon_analytics.adapters.embeddings.tfidf_svd import TfidfSvdEmbeddingProvider
from horizon_analytics.application.dto import build_domain_analyzed
from horizon_analytics.domain.extraction.normalization import TermNormalizer
from horizon_analytics.domain.models import AnalysisParams
from horizon_analytics.domain.narration.extractive import _CONTINUATION_OPENERS
from horizon_analytics.domain.pipeline import (
    AnalysisPipeline,
    PipelineRequest,
    PipelineResult,
    documents_in_direction,
)
from horizon_analytics.domain.scoring.profile import MethodologyProfile

pytestmark = pytest.mark.golden

RUSSIAN_AI = "искусственный интеллект машинное обучение"
RUSSIAN_QUANTUM = "квантовые вычисления"
RUSSIAN_UNKNOWN = "селекция озимой пшеницы"
ENGLISH_AI = "artificial intelligence machine learning"

PROFILE = MethodologyProfile.default()


def analyse(corpus_path: Path, query: str) -> PipelineResult:
    """Полный прогон конвейера — ровно так, как его запускает сценарий использования."""
    return AnalysisPipeline(embedding_provider=TfidfSvdEmbeddingProvider()).run(
        PipelineRequest(
            normalized_query=query,
            documents=load_documents(corpus_path),
            params=AnalysisParams(top_n=15, years_window=8),
            profile=PROFILE,
            window_from=date(2018, 1, 1),
            window_to=date(2025, 12, 31),
            today=date(2026, 1, 1),
            query=query,
        )
    )


def contract_bytes(result: PipelineResult) -> str:
    """Отчёт в форме контракта — то, что действительно уходит наружу.

    Из сравнения исключён единственный блок — ``stageTimingsMs``. Это измерение длительности
    вычисления, а не его результат: два прогона на одной машине расходятся там на проценты,
    и требовать от них совпадения значило бы объявить недетерминизмом работу планировщика.
    Обещание продукта — «один вопрос даёт один отчёт», а не «одинаково быстро».
    """
    payload = build_domain_analyzed(
        result,
        research_request_id="00000000-0000-4000-8000-000000000000",
        attempt=1,
        snapshot_id="snapshot-golden",
        profile=PROFILE,
    )
    payload.pop("stageTimingsMs", None)
    return json.dumps(payload, ensure_ascii=False, sort_keys=False)


@pytest.fixture(scope="module")
def russian_ai_twice(corpus_path: Path) -> tuple[PipelineResult, PipelineResult]:
    return analyse(corpus_path, RUSSIAN_AI), analyse(corpus_path, RUSSIAN_AI)


@pytest.fixture(scope="module")
def russian_quantum(corpus_path: Path) -> PipelineResult:
    return analyse(corpus_path, RUSSIAN_QUANTUM)


@pytest.fixture(scope="module")
def russian_unknown(corpus_path: Path) -> PipelineResult:
    return analyse(corpus_path, RUSSIAN_UNKNOWN)


def test_two_runs_of_the_same_request_serialise_to_identical_bytes(
    russian_ai_twice: tuple[PipelineResult, PipelineResult],
) -> None:
    # Сравнение по байтам контракта, а не по списку ключей тем: разойтись могут баллы в дальнем
    # знаке, порядок индикаторов, набор источников — всё то, что аналитик увидит, а список
    # ключей скроет.
    first, second = russian_ai_twice

    assert contract_bytes(first) == contract_bytes(second)


def test_no_card_says_the_same_thing_twice(
    russian_ai_twice: tuple[PipelineResult, PipelineResult],
) -> None:
    """Определение и мотивация карточки не повторяют друг друга.

    Проводка, а не правило: само правило проверено в `tests/unit/test_definition.py`, а здесь
    держится то, что конвейер им пользуется. Без такой проверки достаточно убрать один именованный
    аргумент из вызова, и отчёт молча вернётся к четырём самоповторяющимся карточкам из
    пятнадцати — ровно так дефект и выглядел, когда его нашли разбором настоящего вывода.
    """
    result, _ = russian_ai_twice

    repeated = [
        outcome.result.trend_key
        for outcome in result.trends
        if outcome.definition
        and (
            outcome.definition in outcome.motivation.problem
            or outcome.definition in outcome.motivation.benefit
        )
    ]

    assert repeated == []


def test_no_definition_is_a_dangling_continuation(
    russian_ai_twice: tuple[PipelineResult, PipelineResult],
) -> None:
    """Определение цитируется без соседей, поэтому обязано сохранять смысл в отрыве.

    «However, deployments still suffer from…» в роли определения ссылается на предшествующее,
    которого аналитик не увидит. Шесть карточек из пятнадцати выглядели так.
    """
    result, _ = russian_ai_twice
    openers = ("however", "moreover", "therefore", "thus", "instead", "conversely", "nevertheless")

    dangling = [
        outcome.result.trend_key
        for outcome in result.trends
        if outcome.definition.split(" ")[0].lower().strip(",") in openers
    ]

    assert dangling == []


def test_no_quoted_motivation_is_a_dangling_continuation(
    russian_ai_twice: tuple[PipelineResult, PipelineResult],
) -> None:
    """Цитата-проблема так же одинока, как определение, и правило обязано быть общим.

    Прежнее обоснование правила утверждало, что определение — единственное поле, стоящее вырванным
    без соседей. Выдача это опровергла: в карточке и в записке проблема подаётся дословной цитатой,
    и другого контекста у неё нет. На четырёх направлениях эталонного корпуса со связки начинались
    29 цитат-проблем из 60 — «However, gate fidelity is limited by…» в роли формулировки проблемы
    спорит с предложением, которого читатель не видит.

    Цена правила измерена и оказалась нулевой: ни одна тема не скатилась в шаблон, потому что
    самостоятельные предложения в источниках были и до того — отбор просто их не предпочитал.
    """
    result, _ = russian_ai_twice

    dangling = [
        (outcome.result.trend_key, field)
        for outcome in result.trends
        for field, text in (
            ("problem", outcome.motivation.problem),
            ("benefit", outcome.motivation.benefit),
        )
        if text.split(" ")[0].lower().strip(",") in _CONTINUATION_OPENERS
    ]

    assert dangling == []


def test_the_report_depends_on_the_direction_that_was_asked(
    russian_ai_twice: tuple[PipelineResult, PipelineResult],
    russian_quantum: PipelineResult,
) -> None:
    # Проверка, которой не было, и потому дефект жил незамеченным: до перекрёстного словаря
    # ЛЮБЫЕ два русских направления давали один и тот же отчёт. Воспроизводимость при этом
    # соблюдалась идеально — одинаковый ответ на разные вопросы её не нарушает.
    ai_keys = [outcome.result.trend_key for outcome in russian_ai_twice[0].trends]
    quantum_keys = [outcome.result.trend_key for outcome in russian_quantum.trends]

    assert ai_keys and quantum_keys
    assert ai_keys != quantum_keys


def test_a_russian_direction_covers_everything_its_english_twin_covers(corpus_path: Path) -> None:
    # Не равенство, а включение — и это осознанное свойство. Статья словаря объявляет ИИ через
    # метки источников целиком, включая коды `cs.AI`, `cs.LG`, `stat.ML`, которых в английской
    # фразе нет. Без кодов четыре пятых препринтов arXiv не относятся ни к какому направлению, а
    # препринт — самый ранний сигнал, ради которого продукт существует.
    #
    # Требование именно включения, а не «примерно того же»: аналитик, пишущий по-русски, не должен
    # терять ничего из того, что увидел бы англоязычный.
    documents = load_documents(corpus_path)
    normalizer = TermNormalizer.from_texts(
        f"{document.title} {document.abstract_text or ' '}" for document in documents
    )

    russian = {
        document.document_id
        for document in documents_in_direction(documents, RUSSIAN_AI, normalizer)
    }
    english = {
        document.document_id
        for document in documents_in_direction(documents, ENGLISH_AI, normalizer)
    }

    assert english
    assert english <= russian


def test_a_resolved_direction_reports_how_many_documents_it_covers(
    russian_ai_twice: tuple[PipelineResult, PipelineResult],
) -> None:
    diagnostics = russian_ai_twice[0].diagnostics

    assert diagnostics["corpus_classified"] > 0
    assert diagnostics["direction_resolved"] > 0


def test_an_unresolved_direction_is_visible_in_the_diagnostics(
    russian_unknown: PipelineResult,
) -> None:
    # Направления нет в словаре. Отбор возвращается на запасной путь «лучшие N по сходству», и
    # отчёт снова перестаёт зависеть от вопроса — но собирается и выглядит целым. Различить этот
    # случай можно только по диагностике: размеченный корпус при нулевом отнесении означает «мы
    # не поняли вопроса», а не «в направлении ничего не происходит».
    diagnostics = russian_unknown.diagnostics

    assert diagnostics["corpus_classified"] > 0
    assert diagnostics["direction_resolved"] == 0


def test_the_report_says_how_far_each_topic_moves_when_the_weights_change(
    russian_ai_twice: tuple[PipelineResult, PipelineResult],
) -> None:
    """Диапазон места приходит на настоящем корпусе и согласован с самим отчётом.

    Веса шести индикаторов выбраны экспертно. Без этого измерения ответ на вопрос «а если бы
    взвесили иначе?» сводится к «доверьтесь нам», и первый же скептик в комитете его получит.
    """
    result, _ = russian_ai_twice
    payload = build_domain_analyzed(
        result,
        research_request_id="00000000-0000-4000-8000-000000000000",
        attempt=1,
        snapshot_id="snapshot-golden",
        profile=PROFILE,
    )

    assert payload["reweightingScenarios"] == 14
    ranges = {trend["trendKey"]: trend["rankStability"] for trend in payload["trends"]}
    assert len(ranges) == len(payload["trends"])
    for trend in payload["trends"]:
        measured = ranges[trend["trendKey"]]
        # Место из отчёта обязано лежать внутри диапазона: базовое взвешивание входит в перебор,
        # и таблица, противоречащая соседней колонке, хуже отсутствующей.
        assert measured["best"] <= trend["rank"] <= measured["worst"]


def test_the_top_of_the_list_does_not_depend_on_how_the_indicators_were_weighted(
    russian_ai_twice: tuple[PipelineResult, PipelineResult],
) -> None:
    """Измерение обязано различать устойчивое и шаткое, иначе оно ничего не сообщает.

    Если бы все темы держались при любом взвешивании, диапазон был бы украшением; если бы плавали
    все — методология не выдерживала бы собственных допущений. На эталонном корпусе верно ни то ни
    другое, и это единственный содержательный исход.
    """
    result, _ = russian_ai_twice
    ranges = {row.trend_key: row for row in result.rank_stability}
    published = [outcome.result.trend_key for outcome in result.trends]

    steady = [key for key in published if ranges[key].holds_in_top(len(published))]
    shaky = [key for key in published if not ranges[key].holds_in_top(len(published))]

    assert steady, "ни одна тема не удержалась — методология не выдерживает собственных допущений"
    assert shaky, "удержались все — измерение ничего не различает и вводит в заблуждение"
    # Первое место не должно зависеть от весов: если зависит, «главная тема направления» —
    # утверждение о настройках, а не о направлении.
    assert ranges[published[0]].best == 1


def test_the_report_says_how_much_of_each_topic_belongs_to_the_direction(
    russian_ai_twice: tuple[PipelineResult, PipelineResult],
) -> None:
    """«Почему эта тема в моём отчёте» — вопрос, на который отвечать было нечем.

    Трассировка объясняет, почему темы **нет**: где кандидат выбыл и по какому правилу. Обратный
    вопрос — почему тема здесь — оставался без ответа, хотя величина, решающая отбор, считалась на
    каждом прогоне и тут же терялась. На этом же и застрял разбор чужой темы во главе отчёта
    (`docs/01-analysis/32-foreign-topic-findings.md`): долю пришлось оценивать по выборке
    доказательств, и оценка оказалась неверной.
    """
    result, _ = russian_ai_twice
    published = {outcome.result.trend_key for outcome in result.trends}

    assert result.direction_share, "корпус размечен кодами, но доля направления не сохранена"
    assert set(result.direction_share) <= published, "доля пришла по теме, которой нет в отчёте"
    assert set(result.direction_share) == published, "доля пришла не по всем опубликованным темам"
    for key, share in result.direction_share.items():
        assert 0.0 <= share <= 1.0, f"доля вне [0, 1] у темы «{key}»: {share}"


def test_the_share_is_a_proportion_and_not_a_similarity(
    russian_ai_twice: tuple[PipelineResult, PipelineResult],
) -> None:
    """Доля обязана быть долей документов, а не косинусом к запросу.

    Та же переменная в запасном режиме (корпус без предметных кодов) означает сходство, и выдавать
    одно за другое нельзя: у доли есть смысл, переживающий смену корпуса, у косинуса — нет. Поэтому
    проверяется не диапазон, а кратность: доля кратна 1/N, где N — число документов темы.
    """
    result, _ = russian_ai_twice

    for outcome in result.trends:
        share = result.direction_share.get(outcome.result.trend_key)
        if share is None:
            continue
        documents = outcome.result.total_documents
        assert documents > 0
        scaled = share * documents
        assert (
            abs(scaled - round(scaled)) < 1e-6
        ), f"доля темы «{outcome.result.trend_key}» не кратна 1/{documents}: {share}"
