"""Шесть индикаторов: не константы, а поведение.

Числа методологии — веса, τ, опорные значения — закреплены отдельной проверкой. Сами формулы не
проверялись ничем: подмена по всему набору, включая эталонный корпус, показала, что новизну можно
заменить на «все темы максимально новы» — и всё останется зелёным. Константа при этом останется на
месте, и проверка чисел не заметит ничего.

Докстрока `IndicatorContext` обещает ровно то, чего не было: «каждый индикатор можно проверить в
отрыве, руками собрав контекст, без всякой инфраструктуры». Обещание выполняется здесь.

Проверяется поведение, а не значения: направление зависимости, границы и вырожденные случаи.
Ожидаемые числа сделали бы тест копией реализации — он краснел бы при любой правке формулы, включая
верную, и не краснел бы при подмене смысла.

Связность (§3.5) закрыта отдельным разделом ниже: ей действительно нужны векторы терминов и матрица
совстречаемости, но и то и другое собирается руками — обещание докстроки выполняется и здесь.
"""

from __future__ import annotations

import numpy as np
import pytest
from tests.conftest import make_candidate, make_document, make_series, make_topic

from horizon_analytics.domain.models import CorpusStats, Topic
from horizon_analytics.domain.scoring.indicators import (
    CoherenceIndicator,
    CooccurrenceIndex,
    DiffusionIndicator,
    GrowthIndicator,
    IndicatorContext,
    NoveltyIndicator,
    WeaknessIndicator,
    linear_fit,
)
from horizon_analytics.domain.scoring.profile import MethodologyProfile

PARAMETERS = MethodologyProfile.default().parameters


def corpus_stats(*, recent_max: int = 100, current_year: int = 2026) -> CorpusStats:
    return CorpusStats(
        periods=("2020", "2021", "2022", "2023"),
        documents_per_period=(100, 100, 100, 100),
        active_source_classes=("PREPRINT", "JOURNAL_ARTICLE", "PATENT"),
        total_documents=400,
        recent_max=recent_max,
        volume_q25=1.0,
        volume_q60=5.0,
        mainstream_threshold=50.0,
        current_year=current_year,
    )


def context(
    *,
    df=(1, 2, 3, 4),
    documents=(),
    first_mention_year: int | None = 2024,
    recent_max: int = 100,
    current_year: int = 2026,
) -> IndicatorContext:
    series = make_series(df)
    return IndicatorContext(
        topic=make_topic("тема", documents=[d.document_id for d in documents] or ["doc-0"]),
        series=series,
        documents=tuple(documents),
        corpus=corpus_stats(recent_max=recent_max, current_year=current_year),
        parameters=PARAMETERS,
        first_mention_year=first_mention_year,
        growth_fit=linear_fit(series),
    )


class TestNovelty:
    def test_a_topic_first_seen_this_year_is_maximally_novel(self) -> None:
        value = NoveltyIndicator().compute(context(first_mention_year=2026)).value

        assert value == pytest.approx(1.0)

    def test_novelty_decays_with_age_and_never_grows(self) -> None:
        # Направление зависимости — то единственное, что делает индикатор новизной. Подмена
        # «все темы новы» проходила все тесты продукта именно потому, что этого никто не требовал.
        values = [
            NoveltyIndicator().compute(context(first_mention_year=year)).value
            for year in (2026, 2024, 2020, 2010)
        ]

        assert values == sorted(values, reverse=True)
        assert values[0] > values[-1]

    def test_an_undated_topic_is_not_novel_rather_than_maximally_novel(self) -> None:
        # BRULE-1 не выполнено — года нет. Ноль здесь означает «не подтверждено», и это
        # противоположно тому, что дало бы отсутствие проверки.
        result = NoveltyIndicator().compute(context(first_mention_year=None))

        assert result.value == 0.0
        assert result.diagnostics["credible"] is False


class TestGrowth:
    def test_a_flat_topic_does_not_grow(self) -> None:
        assert GrowthIndicator().compute(context(df=(5, 5, 5, 5))).value == pytest.approx(0.0)

    def test_faster_growth_scores_higher(self) -> None:
        slow = GrowthIndicator().compute(context(df=(1, 1, 2, 2))).value
        fast = GrowthIndicator().compute(context(df=(1, 4, 16, 64))).value

        assert fast > slow

    def test_a_decaying_topic_does_not_score_above_zero(self) -> None:
        # BRULE-4 обнуляет балл темы с нулевым индикатором, и затухающая тема обязана попадать
        # именно сюда: иначе «зарождающееся» включало бы уходящее.
        assert GrowthIndicator().compute(context(df=(64, 16, 4, 1))).value == pytest.approx(0.0)


class TestWeakness:
    def test_a_quiet_topic_is_weak_and_a_loud_one_is_not(self) -> None:
        quiet = WeaknessIndicator().compute(context(df=(1, 1, 1, 1), recent_max=1000)).value
        loud = WeaknessIndicator().compute(context(df=(400, 400, 400, 400), recent_max=1000)).value

        assert quiet > loud

    def test_the_loudest_topic_of_the_direction_is_not_weak_at_all(self) -> None:
        # Граница: тема, набравшая максимум направления, слабым сигналом не является по
        # определению — и именно это отличает продукт от списка популярного.
        value = WeaknessIndicator().compute(context(df=(0, 0, 0, 500), recent_max=500)).value

        assert value == pytest.approx(0.0, abs=1e-9)


class TestDiffusion:
    def test_a_topic_spread_across_source_classes_diffuses_wider(self) -> None:
        narrow = [
            make_document(f"n{index}", year=2024, source_class="PREPRINT", venue="Venue A")
            for index in range(4)
        ]
        wide = [
            make_document("w0", year=2024, source_class="PREPRINT", venue="Venue A"),
            make_document("w1", year=2024, source_class="JOURNAL_ARTICLE", venue="Venue B"),
            make_document("w2", year=2024, source_class="PATENT", venue="Venue C"),
            make_document("w3", year=2024, source_class="REPO", venue="Venue D"),
        ]

        assert (
            DiffusionIndicator().compute(context(documents=wide)).value
            > DiffusionIndicator().compute(context(documents=narrow)).value
        )

    def test_more_organizations_diffuse_wider_than_one_lab_writing_alone(self) -> None:
        alone = [
            make_document(f"a{index}", year=2024, organizations=("Acme Research Lab",))
            for index in range(4)
        ]
        many = [
            make_document(f"m{index}", year=2024, organizations=(f"Lab {index}",))
            for index in range(4)
        ]

        assert (
            DiffusionIndicator().compute(context(documents=many)).value
            > DiffusionIndicator().compute(context(documents=alone)).value
        )


def cluster(*keys: str) -> Topic:
    """Тема из нескольких терминов — связность имеет смысл только для такой."""
    return Topic(
        key=keys[0],
        label=keys[0],
        members=tuple(
            make_candidate(key, documents=[f"doc-{index}"]) for index, key in enumerate(keys)
        ),
    )


def cooccurrence(
    *, total: int, singles: dict[str, int], pairs: dict[tuple[str, str], int]
) -> CooccurrenceIndex:
    return CooccurrenceIndex(
        total_documents=total,
        document_frequency=singles,
        pair_frequency={
            (left, right) if left <= right else (right, left): count
            for (left, right), count in pairs.items()
        },
    )


def coherence_context(
    topic: Topic, *, vectors, index: CooccurrenceIndex | None
) -> IndicatorContext:
    series = make_series((1, 2, 3, 4))
    return IndicatorContext(
        topic=topic,
        series=series,
        documents=(),
        corpus=corpus_stats(),
        parameters=PARAMETERS,
        first_mention_year=2024,
        term_vectors=None if vectors is None else np.asarray(vectors, dtype=float),
        cooccurrence=index,
        growth_fit=linear_fit(series),
    )


class TestCoherence:
    """§3.5 — «это технология, а не случайная n-грамма».

    Индикатор складывается из двух половин: насколько термины кластера похожи по смыслу и насколько
    они встречаются вместе. Обе половины проверяются по отдельности, потому что подменить можно
    любую, а вклад второй (0.40) достаточно велик, чтобы скрыть отказ первой.
    """

    def test_terms_pointing_the_same_way_cohere_better_than_terms_pointing_apart(self) -> None:
        topic = cluster("a", "b")
        index = cooccurrence(total=100, singles={"a": 10, "b": 10}, pairs={("a", "b"): 8})

        tight = CoherenceIndicator().compute(
            coherence_context(topic, vectors=[[1.0, 0.0], [0.98, 0.2]], index=index)
        )
        loose = CoherenceIndicator().compute(
            coherence_context(topic, vectors=[[1.0, 0.0], [0.0, 1.0]], index=index)
        )

        assert tight.value > loose.value

    def test_terms_that_never_meet_in_one_document_cohere_worse(self) -> None:
        # Вторая половина правила, и именно она отличает технологию от случайного словосочетания:
        # «cycle life» и «syndrome cycle» похожи на вид, но не встречаются вместе ни разу.
        topic = cluster("a", "b")
        vectors = [[1.0, 0.0], [0.98, 0.2]]

        together = CoherenceIndicator().compute(
            coherence_context(
                topic,
                vectors=vectors,
                index=cooccurrence(total=100, singles={"a": 10, "b": 10}, pairs={("a", "b"): 9}),
            )
        )
        apart = CoherenceIndicator().compute(
            coherence_context(
                topic,
                vectors=vectors,
                index=cooccurrence(total=100, singles={"a": 10, "b": 10}, pairs={}),
            )
        )

        assert together.value > apart.value
        assert apart.diagnostics["meanNpmi"] == pytest.approx(-1.0)

    def test_a_single_term_topic_gets_the_declared_default_and_not_a_guess(self) -> None:
        # У темы из одного термина пар не существует, и NPMI неопределён. Продукт обязан подставить
        # объявленное умолчание, а не ноль и не единицу: одно занизило бы такие темы, другое
        # завысило бы, и оба выглядели бы как измерение.
        single = Topic(key="a", label="a", members=(make_candidate("a", documents=["doc-0"]),))

        result = CoherenceIndicator().compute(coherence_context(single, vectors=None, index=None))

        assert result.diagnostics["cNpmi"] == pytest.approx(PARAMETERS.coherence_single_term_npmi)
        assert result.diagnostics["pairsUsed"] == 0

    def test_without_any_embeddings_the_semantic_half_contributes_nothing(self) -> None:
        # Граница честности: нет векторов — нет и семантической связности. Подставлять сюда
        # единицу значило бы утверждать сходство, которого никто не измерял.
        topic = cluster("a", "b")
        index = cooccurrence(total=100, singles={"a": 10, "b": 10}, pairs={("a", "b"): 8})

        result = CoherenceIndicator().compute(coherence_context(topic, vectors=None, index=index))

        assert result.diagnostics["cEmb"] == 0.0
        assert result.diagnostics["embeddingBasis"] == "нет эмбеддингов"
