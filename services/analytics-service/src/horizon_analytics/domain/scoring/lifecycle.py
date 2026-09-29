"""Lifecycle stage classification — methodology §6.

============  =====================================================================
Stage         Condition
============  =====================================================================
MATURING      ``β ≤ 0.20`` or (``patentRatio > 0.40`` and ``df_total ≥ q60``)
ACCELERATING  ``β > 0.50`` and (burst active or ``df_total ≥ q60``)
EMERGING      ``q25 ≤ df_total < q60`` and ``β > 0.20``
EMBRYONIC     ``df_total < q25`` and ``β > 0``
============  =====================================================================

Checked in the order MATURING → ACCELERATING → EMERGING → EMBRYONIC, first match wins.

**Documented gap.** The four rules do not cover every input: a topic with
``0.20 < β ≤ 0.50``, ``df_total ≥ q60`` and ``patentRatio ≤ 0.40`` matches none of them.
It grows faster than the MATURING threshold and slower than the ACCELERATING one, so it is
classified ``EMERGING`` — the residual stage. This is the only place where the
implementation adds a rule the document does not state.
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass

from horizon_analytics.domain.models import CorpusStats, LifecycleStage
from horizon_analytics.domain.scoring.profile import MethodologyParameters

__all__ = ["LifecycleDecision", "classify_lifecycle"]


@dataclass(frozen=True, slots=True)
class LifecycleDecision:
    """Chosen stage together with the rule that fired."""

    stage: LifecycleStage
    rule: str
    diagnostics: Mapping[str, float | int | str | bool]


def classify_lifecycle(
    *,
    slope: float,
    total_documents: int,
    patent_ratio: float,
    burst_active: bool,
    corpus: CorpusStats,
    parameters: MethodologyParameters,
) -> LifecycleDecision:
    """Classify the lifecycle stage of one topic.

    Args:
        slope: ``β`` — the log-linear growth slope of §3.2.
        total_documents: ``df_total`` — documents supporting the topic in the window.
        patent_ratio: the *normalised* patent ratio diagnostic of §3.6.
        burst_active: whether §5 detected a burst.
        corpus: direction level statistics carrying the ``q25`` / ``q60`` quantiles.
        parameters: methodology parameters carrying the slope thresholds.

    Returns:
        The stage, the name of the rule that matched and the diagnostics behind it.
    """
    q25 = corpus.volume_q25
    q60 = corpus.volume_q60
    volume = float(total_documents)
    diagnostics: Mapping[str, float | int | str | bool] = {
        "slope": slope,
        "totalDocuments": total_documents,
        "patentRatio": patent_ratio,
        "burstActive": burst_active,
        "q25": q25,
        "q60": q60,
        "slopeEmerging": parameters.lifecycle_slope_emerging,
        "slopeAccelerating": parameters.lifecycle_slope_accelerating,
    }

    if slope <= parameters.lifecycle_slope_emerging or (
        patent_ratio > parameters.lifecycle_patent_ratio_mature and volume >= q60
    ):
        return LifecycleDecision("MATURING", "maturing", diagnostics)

    if slope > parameters.lifecycle_slope_accelerating and (burst_active or volume >= q60):
        return LifecycleDecision("ACCELERATING", "accelerating", diagnostics)

    if q25 <= volume < q60 and slope > parameters.lifecycle_slope_emerging:
        return LifecycleDecision("EMERGING", "emerging", diagnostics)

    if volume < q25 and slope > 0.0:
        return LifecycleDecision("EMBRYONIC", "embryonic", diagnostics)

    # Residual region documented in the module docstring: growing above the MATURING
    # threshold but neither accelerating nor small — reported as EMERGING.
    return LifecycleDecision("EMERGING", "residual", diagnostics)
