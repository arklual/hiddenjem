"""Вложенность кандидатов: тот же ответ, что у прямого перебора пар.

C-value вычитает из частоты термина среднюю частоту более длинных терминов, его содержащих
(Frantzi et al.). Прямая формулировка перебирает пары и на настоящем корпусе из 1785 работ
OpenAlex — 15 073 кандидата после фильтров — стоила 94.8 с при 0.1 с у соседнего YAKE.

Перечисление подфраз даёт то же самое за 0.08 с. «То же самое» здесь проверяется против прямого
перебора: эталон в этом файле написан по определению из статьи, медленно и очевидно.

Разбор: ``docs/01-analysis/70-two-hundred-million-comparisons.md``.
"""

from __future__ import annotations

import math

from horizon_analytics.domain.extraction.candidates import RawCandidate
from horizon_analytics.domain.extraction.termhood import (
    _nested_frequencies,
    c_values,
    contains_subsequence,
)
from horizon_analytics.domain.models import Posting


def candidate(key: str, term_frequency: int) -> RawCandidate:
    return RawCandidate(
        key=key,
        surface=key,
        surface_forms=(key,),
        postings=(Posting(document_id="d1", occurrences=term_frequency),),
        token_count=len(key.split(" ")),
        is_acronym=False,
    )


def reference(candidates: list[RawCandidate]) -> dict[str, list[int]]:
    """Определение из статьи, в лоб: для каждого кандидата — все более длинные, его содержащие."""
    nested: dict[str, list[int]] = {}
    for item in candidates:
        parts = tuple(item.key.split(" "))
        hits = [
            other.term_frequency
            for other in candidates
            if len(other.key.split(" ")) > len(parts)
            and contains_subsequence(tuple(other.key.split(" ")), parts)
        ]
        if hits:
            nested[item.key] = hits
    return nested


CORPUS = [
    candidate("network", 100),
    candidate("neural", 90),
    candidate("neural network", 40),
    candidate("graph neural network", 12),
    candidate("deep neural network", 8),
    candidate("network neural network", 3),
    candidate("quantum", 50),
    candidate("quantum computing", 20),
]


def test_the_mean_matches_the_pairwise_definition() -> None:
    """Потребитель берёт среднее — оно и сверяется, потому что от порядка списка не зависит."""
    fast = _nested_frequencies(CORPUS)
    slow = reference(CORPUS)
    assert set(fast) == set(slow)
    for key in slow:
        assert sum(fast[key]) / len(fast[key]) == sum(slow[key]) / len(slow[key]), key


def test_a_repeated_subphrase_counts_once() -> None:
    """`network neural network` содержит `network` дважды, а вложенность — свойство пары."""
    assert _nested_frequencies([candidate("network", 100), candidate("network neural network", 3)])[
        "network"
    ] == [3]


def test_a_candidate_is_not_nested_in_itself() -> None:
    assert _nested_frequencies([candidate("neural network", 40)]) == {}


def test_nesting_is_contiguous_not_scattered() -> None:
    """`graph network` не вложено в `graph neural network`: слова стоят не подряд."""
    assert "graph network" not in _nested_frequencies(
        [candidate("graph network", 5), candidate("graph neural network", 12)]
    )


def test_a_phrase_that_is_not_a_candidate_is_not_invented() -> None:
    """Перечисление идёт по подфразам, но засчитываются только те, что кандидатами и являются."""
    assert set(_nested_frequencies([candidate("graph neural network", 12)])) == set()


def test_c_value_subtracts_the_mean_of_the_longer_terms() -> None:
    """Проверка сквозная: от вложенности до самого значения."""
    values = c_values(CORPUS)
    expected = math.log2(2) * (40 - (12 + 8 + 3) / 3)
    assert values["neural network"] == expected
