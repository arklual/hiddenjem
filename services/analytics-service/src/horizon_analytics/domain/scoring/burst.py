"""Burst detection — methodology §5, a simplified two-state Kleinberg (2002) model.

::

    r(t)         = df(c,t) / N(t)
    λ0           = mean r(t) over the first half of the window
    burst(t)     ⇔ r(t) ≥ s · λ0                        s = 2.0
    burst_start  = min t with burst(t)
    burst_weight = Σ_{t: burst(t)} ( r(t)/λ0 − s )

The burst is shown on the timeline chart, feeds the lifecycle rule (§6) and acts as the
first deterministic tie-break when two topics score identically (§7 step 9).
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass

from horizon_analytics.domain.models import Burst, TimeSeries
from horizon_analytics.domain.scoring.profile import MethodologyParameters

__all__ = ["BurstDetection", "detect_burst"]


@dataclass(frozen=True, slots=True)
class BurstDetection:
    """Outcome of burst detection: the burst itself plus diagnostics for the UI."""

    burst: Burst | None
    baseline_rate: float
    rates: tuple[float, ...]
    bursting_periods: tuple[str, ...]

    @property
    def weight(self) -> float:
        """Accumulated burst weight, ``0.0`` when no burst was detected."""
        return self.burst.weight if self.burst is not None else 0.0

    @property
    def active(self) -> bool:
        """Whether a burst was detected at all."""
        return self.burst is not None

    def diagnostics(self) -> Mapping[str, float | int | str | bool]:
        """Raw quantities behind the detection."""
        return {
            "baselineRate": self.baseline_rate,
            "burstingPeriods": len(self.bursting_periods),
            "startPeriod": self.burst.start_period if self.burst else "",
            "weight": self.weight,
        }


def detect_burst(series: TimeSeries, parameters: MethodologyParameters) -> BurstDetection:
    """Run the simplified two-state detector over one topic's series.

    The document leaves ``λ0 = 0`` undefined — the case of a topic that only appears in the
    second half of the window, which is precisely the interesting one. We floor ``λ0`` at
    the rate of ``burst_baseline_floor_documents`` documents spread over the whole corpus:
    the smallest non-zero rate the corpus can express. Without the floor such a topic would
    silently never burst.
    """
    length = series.length
    if length == 0:
        return BurstDetection(burst=None, baseline_rate=0.0, rates=(), bursting_periods=())

    rates = tuple(series.rate(index) for index in range(length))
    half = max(1, length // 2)
    baseline = sum(rates[:half]) / half

    # The floor must be expressed in the same units as `rates`, which are per-period
    # (df(c,t) / N(t)). Dividing by the *whole corpus* instead produced a floor one to two orders
    # of magnitude too small, so any topic absent from the first half of the window compared its
    # rate against a near-zero baseline and reported a burst weight of 30–60 — a pure artefact of
    # the denominator, which then drove both the ACCELERATING stage and the first tie-break.
    periods_with_documents = [count for count in series.corpus_df if count > 0]
    mean_period_documents = (
        sum(periods_with_documents) / len(periods_with_documents) if periods_with_documents else 0.0
    )
    floor = (
        parameters.burst_baseline_floor_documents / mean_period_documents
        if mean_period_documents > 0
        else 0.0
    )
    baseline_effective = max(baseline, floor)
    if baseline_effective <= 0.0:
        return BurstDetection(burst=None, baseline_rate=baseline, rates=rates, bursting_periods=())

    scale = parameters.burst_scale
    bursting: list[int] = [
        index for index in range(length) if rates[index] >= scale * baseline_effective
    ]
    if not bursting:
        return BurstDetection(
            burst=None, baseline_rate=baseline_effective, rates=rates, bursting_periods=()
        )

    weight = sum(rates[index] / baseline_effective - scale for index in bursting)
    start = series.periods[bursting[0]]
    return BurstDetection(
        burst=Burst(start_period=start, weight=max(0.0, weight)),
        baseline_rate=baseline_effective,
        rates=rates,
        bursting_periods=tuple(series.periods[index] for index in bursting),
    )
