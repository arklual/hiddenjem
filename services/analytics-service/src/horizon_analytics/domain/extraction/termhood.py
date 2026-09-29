"""Termhood: C-value (Frantzi et al., 2000) + a YAKE-like statistical weight.

Methodology §7 step 3: *"термхуд по C-value (Frantzi et al.) + статистический вес
(YAKE-подобный)"*.

C-value rewards multi-word units that are frequent **and not merely fragments of longer
units**; YAKE penalises words that behave like background vocabulary (frequent everywhere,
in many contexts, spread over all sentences). Combining them cancels each other's known
failure mode: C-value alone likes long boilerplate, YAKE alone likes rare noise.

::

    C-value(a) = log2|a| · f(a)                                        a not nested
    C-value(a) = log2|a| · ( f(a) − (1/|T_a|)·Σ_{b∈T_a} f(b) )         a nested in T_a

    termhood(a) = 0.60 · norm(C-value) + 0.40 · 1/(1 + YAKE(a))
"""

from __future__ import annotations

import math
from collections.abc import Mapping, Sequence
from dataclasses import dataclass

from horizon_analytics.domain.extraction.candidates import CorpusTokenStatistics, RawCandidate

__all__ = [
    "TermhoodScore",
    "c_values",
    "compute_termhood",
    "contains_subsequence",
    "yake_scores",
]


@dataclass(frozen=True, slots=True)
class TermhoodScore:
    """Combined termhood of one candidate with both components exposed."""

    key: str
    termhood: float
    c_value: float
    yake: float
    yake_quality: float


def _nested_frequencies(
    candidates: Sequence[RawCandidate],
) -> dict[str, list[int]]:
    """For every candidate, the frequencies of the longer candidates containing it.

    Containment is tested on the *normalised* key split into stems, so that
    ``graph neural network`` correctly nests ``neural network``.

    Считается не перебором пар, а перечислением подфраз. Прямая формулировка Frantzi et al. —
    «для каждого кандидата найти все более длинные, содержащие его» — квадратична: на настоящем
    корпусе из 1785 работ OpenAlex после фильтров остаётся 15 073 кандидата, то есть 227 млн
    проверок вложенности, и замер показал 91.3 с при 0.1 с у соседнего YAKE.

    Обратный ход даёт тот же ответ за линейное время: у кандидата длиной m токенов подфраз
    ``m(m-1)/2``, а m ограничено ``ngram_max``, поэтому работы — десятки тысяч обращений к
    множеству ключей вместо сотен миллионов сравнений.

    Ответ именно тот же, а не близкий. Два свойства это обеспечивают: во-первых, повторная
    подфраза внутри одного длинного кандидата засчитывается один раз — как и при проверке
    вложенности, которая отвечала «да» или «нет», а не «сколько раз»; во-вторых, потребитель берёт
    среднее по списку частот, а среднее от порядка не зависит, и складываются целые числа, где
    порядок не влияет и на разрядность.
    """
    keys = {tuple(candidate.key.split(" ")) for candidate in candidates}

    nested: dict[str, list[int]] = {}
    for candidate in candidates:
        parts = tuple(candidate.key.split(" "))
        if len(parts) < 2:
            continue
        seen: set[tuple[str, ...]] = set()
        for size in range(1, len(parts)):
            for start in range(len(parts) - size + 1):
                shorter = parts[start : start + size]
                if shorter in seen or shorter not in keys:
                    continue
                seen.add(shorter)
                nested.setdefault(" ".join(shorter), []).append(candidate.term_frequency)
    return nested


def contains_subsequence(haystack: tuple[str, ...], needle: tuple[str, ...]) -> bool:
    """Whether ``needle`` occurs as a contiguous sub-sequence of ``haystack``."""
    size = len(needle)
    if size > len(haystack):
        return False
    return any(
        haystack[start : start + size] == needle for start in range(len(haystack) - size + 1)
    )


def c_values(candidates: Sequence[RawCandidate]) -> dict[str, float]:
    """Compute the C-value of every candidate."""
    nested = _nested_frequencies(candidates)
    result: dict[str, float] = {}
    for candidate in candidates:
        length = max(1, candidate.token_count)
        # log2(1) = 0 would erase every unigram; Frantzi et al. use the +0.1 offset.
        log_length = math.log2(length + 0.1) if length == 1 else math.log2(length)
        frequency = float(candidate.term_frequency)
        hits = nested.get(candidate.key)
        if hits:
            frequency -= sum(hits) / len(hits)
        result[candidate.key] = log_length * frequency
    return result


def yake_scores(
    candidates: Sequence[RawCandidate], statistics: CorpusTokenStatistics
) -> dict[str, float]:
    """Compute the YAKE keyword score of every candidate (lower is better).

    Implements Campos et al. (2020) §3: ``Casing``, ``Position``, ``Frequency``,
    ``Relatedness to context`` and ``Different sentences`` per word, combined into

    ``S(kw) = Π S(w) / ( TF(kw) · (1 + Σ S(w)) )``.
    """
    word_scores: dict[str, float] = {}
    denominator = statistics.mean_frequency + statistics.std_frequency
    for term, entry in sorted(statistics.tokens.items()):
        frequency = float(entry.frequency)
        casing = max(entry.upper_count, entry.capitalized_count) / (1.0 + math.log(frequency))
        position = math.log(math.log(3.0 + entry.median_sentence_index))
        normalized_frequency = frequency / denominator if denominator > 0.0 else 0.0
        max_frequency = float(statistics.max_frequency) or 1.0
        left = entry.distinct_left / frequency if frequency > 0.0 else 0.0
        right = entry.distinct_right / frequency if frequency > 0.0 else 0.0
        relatedness = 1.0 + (left + right) * (frequency / max_frequency)
        different = entry.sentence_frequency / statistics.total_sentences
        divisor = casing + normalized_frequency / relatedness + different / relatedness
        word_scores[term] = (relatedness * position) / divisor if divisor > 0.0 else 0.0

    result: dict[str, float] = {}
    for candidate in candidates:
        words = candidate.key.split(" ")
        scores = [word_scores.get(word, 1.0) for word in words]
        product = math.prod(scores)
        total = math.fsum(scores)
        term_frequency = float(candidate.term_frequency) or 1.0
        result[candidate.key] = product / (term_frequency * (1.0 + total))
    return result


def compute_termhood(
    candidates: Sequence[RawCandidate],
    statistics: CorpusTokenStatistics,
    *,
    c_value_weight: float = 0.60,
    yake_weight: float = 0.40,
) -> Mapping[str, TermhoodScore]:
    """Combine C-value and YAKE into one termhood in ``[0, 1]``.

    The C-value is normalised logarithmically against the corpus maximum: a linear
    normalisation lets a single very frequent term flatten every other candidate to zero.
    """
    raw_c = c_values(candidates)
    raw_yake = yake_scores(candidates, statistics)
    max_c = max((value for value in raw_c.values() if value > 0.0), default=0.0)
    log_max_c = math.log1p(max_c) if max_c > 0.0 else 0.0

    scores: dict[str, TermhoodScore] = {}
    for candidate in sorted(candidates, key=lambda item: item.key):
        c_value = raw_c.get(candidate.key, 0.0)
        normalized_c = math.log1p(max(0.0, c_value)) / log_max_c if log_max_c > 0.0 else 0.0
        normalized_c = max(0.0, min(1.0, normalized_c))
        yake = raw_yake.get(candidate.key, 0.0)
        quality = 1.0 / (1.0 + yake) if yake >= 0.0 else 0.0
        termhood = c_value_weight * normalized_c + yake_weight * quality
        scores[candidate.key] = TermhoodScore(
            key=candidate.key,
            termhood=max(0.0, min(1.0, termhood)),
            c_value=c_value,
            yake=yake,
            yake_quality=quality,
        )
    return scores
