"""Пространство эмбеддингов подгоняется под корпус, а не под запрос.

Замер на 1785 работах OpenAlex: три направления по одному корпусу стоили 480 с, из них 168 с —
двукратная подгонка того же самого пространства. После правки: 164.9 с, 89.0 с, 91.6 с.

Экономия имеет право на существование ровно постольку, поскольку результат от неё не меняется, —
и это здесь и проверяется. Разбор: ``docs/01-analysis/69-the-space-that-was-fitted-three-times.md``.
"""

from __future__ import annotations

import numpy as np

from horizon_analytics.adapters.embeddings.tfidf_svd import TfidfSvdEmbeddingProvider

CORPUS = [
    "solid state batteries with sulfide electrolytes",
    "renewable energy supply and grid stability",
    "network security and intrusion detection systems",
    "genome editing with base editors",
]
OTHER = [*CORPUS[:2], "quantum error correction with surface codes"]


def test_refitting_the_same_corpus_leaves_the_space_identical() -> None:
    """Пропускается вычисление, дающее то же состояние, — значит, векторы совпадают побитно."""
    provider = TfidfSvdEmbeddingProvider()
    provider.fit(CORPUS)
    before = provider.embed(CORPUS)
    provider.fit(CORPUS)
    assert np.array_equal(provider.embed(CORPUS), before)


def test_a_different_corpus_refits() -> None:
    """Опознание по содержимому, а не «уже подогнан»: другой корпус — другое пространство."""
    provider = TfidfSvdEmbeddingProvider()
    provider.fit(CORPUS)
    before = provider.embed(CORPUS)
    provider.fit(OTHER)
    assert not np.array_equal(provider.embed(CORPUS), before)


def test_order_of_the_corpus_counts_as_a_difference() -> None:
    """От порядка зависят и словарь, и знаки компонент, поэтому он входит в отпечаток."""
    provider = TfidfSvdEmbeddingProvider()
    provider.fit(CORPUS)
    before = provider.embed(CORPUS)
    provider.fit(list(reversed(CORPUS)))
    assert not np.array_equal(provider.embed(CORPUS), before)


def test_an_empty_corpus_forgets_the_previous_one() -> None:
    """Иначе пустой корпус молча отвечал бы векторами прошлого."""
    provider = TfidfSvdEmbeddingProvider()
    provider.fit(CORPUS)
    provider.fit([])
    assert np.array_equal(provider.embed(CORPUS), np.zeros((len(CORPUS), provider.dimension)))


def test_a_fresh_provider_and_a_reused_one_agree() -> None:
    """Повторное использование не должно отличаться от чистого запуска ничем."""
    reused = TfidfSvdEmbeddingProvider()
    reused.fit(OTHER)
    reused.fit(CORPUS)
    fresh = TfidfSvdEmbeddingProvider()
    fresh.fit(CORPUS)
    assert np.array_equal(reused.embed(CORPUS), fresh.embed(CORPUS))


def test_the_same_corpus_after_an_empty_one_is_fitted_again() -> None:
    """Пустой корпус обнуляет словарь, а отпечаток остаётся — подогнать надо заново."""
    provider = TfidfSvdEmbeddingProvider()
    provider.fit(CORPUS)
    provider.fit([])
    provider.fit(CORPUS)
    fresh = TfidfSvdEmbeddingProvider()
    fresh.fit(CORPUS)
    assert np.array_equal(provider.embed(CORPUS), fresh.embed(CORPUS))
