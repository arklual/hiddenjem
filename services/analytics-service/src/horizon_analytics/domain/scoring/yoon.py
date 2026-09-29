"""Degree of Visibility / Degree of Diffusion — Yoon (2012), methodology §3.2 (б).

::

    DoV(c,t) = ( tf(c,t) / N(t) ) · ( 1 − tw·(n − t) )
    DoD(c,t) = ( df(c,t) / N(t) ) · ( 1 − tw·(n − t) )     tw = 0.05

The mean increase rates of these two series are the coordinates of the Keyword Emergence
Map (KEM) and the Keyword Issue Map (KIM); the "high growth × low frequency" quadrant is
the weak-signal quadrant. The series are shipped to the frontend for charts and, as the
document states explicitly, do **not** enter the final score.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

from horizon_analytics.domain.models import TimeSeries, safe_div
from horizon_analytics.domain.scoring.profile import MethodologyParameters

__all__ = ["YoonSeries", "compute_yoon"]


@dataclass(frozen=True, slots=True)
class YoonSeries:
    """DoV/DoD series plus the KEM/KIM coordinates derived from them."""

    dov: tuple[float, ...]
    dod: tuple[float, ...]
    dov_mean: float
    dod_mean: float
    dov_growth_rate: float
    dod_growth_rate: float

    @property
    def is_weak_signal_quadrant(self) -> bool:
        """True when growth is positive on both axes while visibility stays low.

        This is the qualitative reading of the KEM/KIM quadrant, exposed as a diagnostic;
        the ranking itself never consults it.
        """
        return self.dov_growth_rate > 0.0 and self.dod_growth_rate > 0.0


def _mean_increase_rate(values: tuple[float, ...]) -> float:
    """Mean period-over-period relative increase, skipping undefined transitions."""
    rates: list[float] = []
    for index in range(1, len(values)):
        previous = values[index - 1]
        if previous > 0.0:
            rates.append(values[index] / previous - 1.0)
    if not rates:
        return 0.0
    return math.fsum(rates) / len(rates)


def compute_yoon(series: TimeSeries, parameters: MethodologyParameters) -> YoonSeries:
    """Compute the DoV/DoD series of one topic over the analysis window."""
    n = series.length
    time_weight = parameters.yoon_time_weight
    dov: list[float] = []
    dod: list[float] = []
    for index in range(n):
        t = index + 1
        decay = 1.0 - time_weight * (n - t)
        corpus = float(series.corpus_df[index])
        dov.append(safe_div(float(series.tf[index]), corpus) * decay)
        dod.append(safe_div(float(series.df[index]), corpus) * decay)
    dov_tuple = tuple(dov)
    dod_tuple = tuple(dod)
    return YoonSeries(
        dov=dov_tuple,
        dod=dod_tuple,
        dov_mean=(math.fsum(dov_tuple) / n if n else 0.0),
        dod_mean=(math.fsum(dod_tuple) / n if n else 0.0),
        dov_growth_rate=_mean_increase_rate(dov_tuple),
        dod_growth_rate=_mean_increase_rate(dod_tuple),
    )
