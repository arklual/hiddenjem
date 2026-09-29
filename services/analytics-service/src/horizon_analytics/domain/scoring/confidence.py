"""Confidence — methodology §3.7.

Confidence operationalises Rotolo's *uncertainty & ambiguity* attribute. It is **reported,
never maximised**: it does not enter the score, it only decides whether the card is flagged
"низкая доказательная база" (BRULE-6).

``confidence = 0.35·evidence + 0.25·diversity + 0.20·fit + 0.20·span``
"""

from __future__ import annotations

import math
from collections.abc import Mapping
from dataclasses import dataclass

from horizon_analytics.domain.models import TimeSeries, clamp01, safe_div
from horizon_analytics.domain.scoring.profile import MethodologyParameters

__all__ = ["ConfidenceResult", "compute_confidence"]


@dataclass(frozen=True, slots=True)
class ConfidenceResult:
    """Confidence value with its four components and a Russian explanation."""

    value: float
    diagnostics: Mapping[str, float | int | str | bool]
    explanation: str


def _fit(r_squared: float, periods: int) -> float:
    """Качество подгонки с поправкой на число точек — скорректированный ``R²``.

    Сырой ``R²`` нельзя брать как есть, и это не вопрос вкуса. Прямая проходит через две точки
    **точно**, остатка нет по построению, и ``R² = 1.0`` получается не потому, что рост описан
    хорошо, а потому, что описывать было нечего. Замер по эталонному корпусу: у тем с рядом из
    двух-трёх точек медианный ``R²`` равен 0.992, у тем с шестью точками и больше — 0.789. То есть
    показатель уверенности был тем выше, чем меньше данных.

    Поправка стандартная и ничего не подбирает: ``R²_adj = 1 − (1 − R²)·(n − 1)/(n − 2)`` —
    отношение остаточной дисперсии к полной, делённое на степени свободы. Регрессия тратит два
    параметра, наклон и свободный член; при ``n = 2`` остаточных степеней свободы ноль, поправка не
    определена, и честное значение здесь — ноль, а не единица.

    Args:
        r_squared: ``R²`` регрессии роста.
        periods: число периодов ряда с ненулевыми данными — точек, по которым построена регрессия.

    Returns:
        Скорректированный ``R²`` в ``[0, 1]``; ноль при двух точках и меньше.
    """
    if periods <= 2:
        return 0.0
    return clamp01(1.0 - (1.0 - clamp01(r_squared)) * (periods - 1) / (periods - 2))


def compute_confidence(
    *,
    document_frequency: int,
    source_class_count: int,
    r_squared: float,
    series: TimeSeries,
    parameters: MethodologyParameters,
    threshold: float,
) -> ConfidenceResult:
    """Compute the evidential confidence of one topic.

    Args:
        document_frequency: ``df(c)`` — number of documents supporting the topic.
        source_class_count: ``|S(c)|`` — number of distinct source classes.
        r_squared: ``R²`` of the growth regression, reused here per §3.7.
        series: the topic's period series, used for the ``span`` component.
        parameters: methodology parameters carrying the reference constants.
        threshold: BRULE-6 threshold below which the trend is flagged low-evidence.

    Returns:
        The confidence value, its components and a human readable Russian explanation.
    """
    evidence = clamp01(
        safe_div(math.log1p(document_frequency), math.log1p(parameters.confidence_evidence_ref))
    )
    diversity = clamp01(
        safe_div(float(source_class_count), float(parameters.confidence_diversity_ref))
    )
    fit = _fit(r_squared, series.periods_with_data())
    span_reference = max(1, min(series.length, parameters.confidence_span_ref))
    span = clamp01(safe_div(float(series.periods_with_data()), float(span_reference)))

    value = clamp01(
        parameters.confidence_evidence_weight * evidence
        + parameters.confidence_diversity_weight * diversity
        + parameters.confidence_fit_weight * fit
        + parameters.confidence_span_weight * span
    )
    low_evidence = value < threshold
    marker = " (ниже порога — низкая доказательная база)" if low_evidence else ""
    return ConfidenceResult(
        value=value,
        diagnostics={
            "evidence": evidence,
            "diversity": diversity,
            "fit": fit,
            # Сырой R² рядом со скорректированным: без него читатель диагностик не может понять,
            # почему «качество подгонки» ниже, чем R² в разборе индикатора роста.
            "rSquaredRaw": clamp01(r_squared),
            "span": span,
            "documentFrequency": document_frequency,
            "sourceClassCount": source_class_count,
            "periodsWithData": series.periods_with_data(),
            "spanReference": span_reference,
            "threshold": threshold,
            "lowEvidence": low_evidence,
        },
        explanation=(
            f"Доказательная база: {document_frequency} док. (evidence {evidence:.3f}), "
            f"{source_class_count} классов источников (diversity {diversity:.3f}), "
            f"качество подгонки {fit:.3f} (скорректированный R² по "
            f"{series.periods_with_data()} точкам, сырой {clamp01(r_squared):.3f}), покрытие периодов "
            f"{series.periods_with_data()}/{span_reference} (span {span:.3f}); "
            f"confidence = {value:.3f} при пороге {threshold:.2f}{marker}."
        ),
    )
