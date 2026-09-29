"""Score aggregation — methodology §4.

The default aggregator is the **weighted geometric mean**, chosen because indicators must
not compensate for each other (BRULE-4): a topic with no novelty cannot buy the score back
with growth.

``ES(c) = 100 · Π_k x_k^{w_k}``

Each indicator is decomposed for the UI into

* ``multiplier m_k = x_k^{w_k} ∈ [0, 1]``, so that ``100 · Π m_k = ES`` exactly, and
* ``shortfall_k = −w_k·ln(max(x_k, 1e-9))``, normalised into shares — "who pulls hardest".

Alternative aggregators are selected through the profile and echoed in the result.
"""

from __future__ import annotations

import math
from collections.abc import Mapping
from dataclasses import dataclass
from typing import ClassVar, Protocol, runtime_checkable

from horizon_analytics.domain.models import (
    INDICATOR_NAMES,
    AggregatorName,
    IndicatorName,
    clamp01,
)

__all__ = [
    "AggregationOutcome",
    "IndicatorBreakdown",
    "MinBoundAggregator",
    "ScoreAggregator",
    "WeightedArithmeticAggregator",
    "WeightedGeometricAggregator",
    "get_aggregator",
]


@dataclass(frozen=True, slots=True)
class IndicatorBreakdown:
    """Per-indicator decomposition of the aggregated score."""

    name: IndicatorName
    value: float
    weight: float
    multiplier: float
    shortfall_share: float


@dataclass(frozen=True, slots=True)
class AggregationOutcome:
    """Aggregated score together with its per-indicator decomposition."""

    score: float
    breakdown: tuple[IndicatorBreakdown, ...]
    zeroed_by: tuple[IndicatorName, ...] = ()
    """Indicators that were exactly zero and therefore zeroed the score (BRULE-4)."""


@runtime_checkable
class ScoreAggregator(Protocol):
    """Strategy combining indicator values into the final emergence score."""

    name: ClassVar[AggregatorName]

    def aggregate(
        self,
        values: Mapping[IndicatorName, float],
        weights: Mapping[IndicatorName, float],
        *,
        floor: float = 1e-9,
        decimals: int = 6,
    ) -> AggregationOutcome:
        """Aggregate indicator values into a score in ``[0, 100]``."""
        ...


def _ordered(
    values: Mapping[IndicatorName, float], weights: Mapping[IndicatorName, float]
) -> tuple[tuple[IndicatorName, float, float], ...]:
    """Zip values and weights in the canonical indicator order (determinism, §9)."""
    return tuple(
        (name, clamp01(values[name]), weights[name])
        for name in INDICATOR_NAMES
        if name in values and name in weights
    )


def _shortfall_shares(
    items: tuple[tuple[IndicatorName, float, float], ...], floor: float
) -> dict[IndicatorName, float]:
    """Normalised ``−w·ln(x)`` shares; all zero when every indicator is perfect."""
    raw = {name: -weight * math.log(max(value, floor)) for name, value, weight in items}
    total = math.fsum(raw.values())
    if total <= 0.0:
        return dict.fromkeys(raw, 0.0)
    return {name: clamp01(amount / total) for name, amount in raw.items()}


def _zeroed(items: tuple[tuple[IndicatorName, float, float], ...]) -> tuple[IndicatorName, ...]:
    """Indicators that are exactly zero while carrying a positive weight (BRULE-4)."""
    return tuple(name for name, value, weight in items if value <= 0.0 and weight > 0.0)


@dataclass(frozen=True, slots=True)
class WeightedGeometricAggregator:
    """``ES = 100 · Π x_k^{w_k}`` — the default, non-compensatory aggregator.

    The product form is used rather than ``100·exp(Σ w·ln x)`` because the two agree for
    every ``x > 0`` while only the product yields exactly ``0`` when an indicator is ``0``,
    which is what BRULE-4 demands. The ``1e-9`` floor of the document is what protects the
    logarithm used for ``shortfallShare``.
    """

    name: ClassVar[AggregatorName] = "WEIGHTED_GEOMETRIC"

    def aggregate(
        self,
        values: Mapping[IndicatorName, float],
        weights: Mapping[IndicatorName, float],
        *,
        floor: float = 1e-9,
        decimals: int = 6,
    ) -> AggregationOutcome:
        """Aggregate with the weighted geometric mean."""
        items = _ordered(values, weights)
        shares = _shortfall_shares(items, floor)
        product = 1.0
        breakdown: list[IndicatorBreakdown] = []
        for name, value, weight in items:
            multiplier = clamp01(value**weight)
            product *= multiplier
            breakdown.append(
                IndicatorBreakdown(
                    name=name,
                    value=value,
                    weight=weight,
                    multiplier=multiplier,
                    shortfall_share=shares[name],
                )
            )
        score = round(100.0 * product, decimals)
        return AggregationOutcome(
            score=max(0.0, min(100.0, score)),
            breakdown=tuple(breakdown),
            zeroed_by=_zeroed(items),
        )


@dataclass(frozen=True, slots=True)
class WeightedArithmeticAggregator:
    """``ES = 100 · Σ w_k·x_k`` — compensatory baseline used by ablation studies.

    BRULE-4 is a *business* rule and is enforced regardless of the aggregator: a zero
    indicator still zeroes the score. ``multiplier`` keeps the geometric meaning
    (``x^w``) so that the UI decomposition stays comparable across aggregators; the
    identity ``100·Π m_k = ES`` therefore holds exactly only for ``WEIGHTED_GEOMETRIC``.
    """

    name: ClassVar[AggregatorName] = "WEIGHTED_ARITHMETIC"

    def aggregate(
        self,
        values: Mapping[IndicatorName, float],
        weights: Mapping[IndicatorName, float],
        *,
        floor: float = 1e-9,
        decimals: int = 6,
    ) -> AggregationOutcome:
        """Aggregate with the weighted arithmetic mean."""
        items = _ordered(values, weights)
        zeroed = _zeroed(items)
        total_weight = math.fsum(weight for _, _, weight in items)
        weighted = math.fsum(value * weight for _, value, weight in items)
        raw = 0.0 if zeroed else 100.0 * (weighted / total_weight if total_weight > 0.0 else 0.0)

        deficits = {name: weight * (1.0 - value) for name, value, weight in items}
        deficit_total = math.fsum(deficits.values())
        breakdown = tuple(
            IndicatorBreakdown(
                name=name,
                value=value,
                weight=weight,
                multiplier=clamp01(value**weight),
                shortfall_share=(
                    clamp01(deficits[name] / deficit_total) if deficit_total > 0.0 else 0.0
                ),
            )
            for name, value, weight in items
        )
        return AggregationOutcome(
            score=max(0.0, min(100.0, round(raw, decimals))),
            breakdown=breakdown,
            zeroed_by=zeroed,
        )


@dataclass(frozen=True, slots=True)
class MinBoundAggregator:
    """``ES = 100 · min_k x_k`` over the positively weighted indicators.

    The strictest reading of "indicators do not compensate": the score is capped by the
    weakest attribute. Weights select which indicators participate, and the whole shortfall
    is attributed to the arg-min (shared equally on ties).
    """

    name: ClassVar[AggregatorName] = "MIN_BOUND"

    def aggregate(
        self,
        values: Mapping[IndicatorName, float],
        weights: Mapping[IndicatorName, float],
        *,
        floor: float = 1e-9,
        decimals: int = 6,
    ) -> AggregationOutcome:
        """Aggregate by taking the minimum indicator."""
        items = _ordered(values, weights)
        participating = [(name, value, weight) for name, value, weight in items if weight > 0.0]
        if not participating:
            return AggregationOutcome(score=0.0, breakdown=(), zeroed_by=())
        minimum = min(value for _, value, _ in participating)
        losers = [name for name, value, _ in participating if value <= minimum]
        share = 1.0 / len(losers)
        breakdown = tuple(
            IndicatorBreakdown(
                name=name,
                value=value,
                weight=weight,
                multiplier=clamp01(value**weight),
                shortfall_share=(share if name in losers else 0.0),
            )
            for name, value, weight in items
        )
        return AggregationOutcome(
            score=max(0.0, min(100.0, round(100.0 * minimum, decimals))),
            breakdown=breakdown,
            zeroed_by=_zeroed(items),
        )


_AGGREGATORS: dict[AggregatorName, ScoreAggregator] = {
    "WEIGHTED_GEOMETRIC": WeightedGeometricAggregator(),
    "WEIGHTED_ARITHMETIC": WeightedArithmeticAggregator(),
    "MIN_BOUND": MinBoundAggregator(),
}


def get_aggregator(name: AggregatorName) -> ScoreAggregator:
    """Look up an aggregator by its contract name."""
    try:
        return _AGGREGATORS[name]
    except KeyError as error:  # pragma: no cover - guarded by the JSON schema enum
        raise ValueError(f"unknown aggregator: {name!r}") from error
