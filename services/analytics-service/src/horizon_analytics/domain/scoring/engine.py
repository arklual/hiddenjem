"""Emergence scoring engine — the domain service tying §3 to §6 together.

For one topic the engine

1. runs the six indicators in canonical order (§3.1 – §3.6),
2. computes the confidence (§3.7) reusing the regression ``R²`` of ``growth``,
3. aggregates through the profile's :class:`ScoreAggregator` (§4),
4. detects the burst (§5) and classifies the lifecycle stage (§6),
5. attaches the Yoon DoV/DoD series (§3.2 б),

and returns an immutable :class:`EmergenceResult`. It performs no I/O and reads no clock:
the current year arrives inside :class:`CorpusStats`.
"""

from __future__ import annotations

import math
from dataclasses import dataclass

from horizon_analytics.domain.models import (
    INDICATOR_NAMES,
    EmergenceResult,
    IndicatorName,
    IndicatorValue,
    ScoredIndicator,
)
from horizon_analytics.domain.scoring.aggregators import ScoreAggregator, get_aggregator
from horizon_analytics.domain.scoring.burst import detect_burst
from horizon_analytics.domain.scoring.confidence import compute_confidence
from horizon_analytics.domain.scoring.indicators import (
    Indicator,
    IndicatorContext,
    build_indicators,
    linear_fit,
)
from horizon_analytics.domain.scoring.lifecycle import classify_lifecycle
from horizon_analytics.domain.scoring.profile import MethodologyProfile
from horizon_analytics.domain.scoring.yoon import compute_yoon

__all__ = ["EmergenceEngine"]


@dataclass(frozen=True, slots=True)
class EmergenceEngine:
    """Computes the emergence score of a topic from its context."""

    profile: MethodologyProfile
    indicators: tuple[Indicator, ...] = ()

    @classmethod
    def create(cls, profile: MethodologyProfile) -> EmergenceEngine:
        """Build an engine with the six standard indicators."""
        return cls(profile=profile, indicators=build_indicators())

    def _renormalised_weights(self, values: object) -> dict[IndicatorName, float]:
        """Веса измеренных индикаторов, приведённые к сумме единица.

        При полном наборе возвращает профиль без изменений — до знака, чтобы эталонные снимки
        не сдвинулись.
        """
        present = {
            name: weight
            for name, weight in self.profile.weights.items()
            if name in values  # type: ignore[operator]
        }
        total = math.fsum(present.values())
        if not present or total <= 0.0:
            return dict(self.profile.weights)
        if abs(total - 1.0) < 1e-12:
            return present
        return {name: weight / total for name, weight in present.items()}

    def score(self, context: IndicatorContext) -> EmergenceResult:
        """Score one topic end to end.

        Args:
            context: everything the indicators need for this topic. ``growth_fit`` is
                computed here when absent so that ``growth`` and ``confidence`` provably
                share one regression.

        Returns:
            The immutable scoring result, ready to be ranked and serialised.
        """
        parameters = self.profile.parameters
        fit = (
            context.growth_fit
            if context.growth_fit is not None
            else linear_fit(context.series, relative=context.parameters.growth_relative)
        )
        prepared = (
            context
            if context.growth_fit is not None
            else IndicatorContext(
                topic=context.topic,
                series=context.series,
                documents=context.documents,
                corpus=context.corpus,
                parameters=context.parameters,
                first_mention_year=context.first_mention_year,
                term_vectors=context.term_vectors,
                document_vectors=context.document_vectors,
                cooccurrence=context.cooccurrence,
                growth_fit=fit,
            )
        )

        computed: dict[IndicatorName, IndicatorValue] = {}
        for indicator in self.indicators:
            value = indicator.compute(prepared)
            computed[value.name] = value

        # Неизмеренный индикатор в свёртку не идёт, а веса остальных нормируются заново.
        #
        # BRULE-4 говорит про измеренный ноль: тема без новизны не может добрать балл ростом. Про
        # корпус, в котором индикатор нечем посчитать, он не говорит ничего — а обнулять балл за
        # состав источников значит отвечать не на тот вопрос. Замер: живой корпус по
        # кибербезопасности (arXiv, Crossref, GitHub — ни одного патента, цитирований у пятой
        # части документов) обнулял 44 темы из 47, и отчёт выходил пустым.
        #
        # Нормировка обязательна: без неё сумма весов меньше единицы, произведение степеней
        # систематически завышается, и балл с пятью индикаторами нельзя сравнивать с баллом с
        # шестью — а сравнивают их в одном списке.
        values = {name: item.value for name, item in computed.items() if item.measured}
        unmeasured = tuple(name for name, item in computed.items() if not item.measured)
        weights = self._renormalised_weights(values)
        aggregator: ScoreAggregator = get_aggregator(self.profile.aggregator)
        outcome = aggregator.aggregate(
            values,
            weights,
            floor=parameters.score_floor,
            decimals=parameters.score_decimals,
        )

        source_classes = len(prepared.documents_by_source_class())
        confidence = compute_confidence(
            document_frequency=prepared.topic.document_frequency,
            source_class_count=source_classes,
            r_squared=fit.r_squared,
            series=prepared.series,
            parameters=parameters,
            threshold=self.profile.confidence_threshold,
        )

        detection = detect_burst(prepared.series, parameters)
        patent_ratio = float(computed["impact"].diagnostics.get("patentRatio", 0.0) or 0.0)
        lifecycle = classify_lifecycle(
            slope=fit.slope,
            total_documents=prepared.topic.document_frequency,
            patent_ratio=patent_ratio,
            burst_active=detection.active,
            corpus=prepared.corpus,
            parameters=parameters,
        )
        yoon = compute_yoon(prepared.series, parameters)

        aggregated = {
            item.name: ScoredIndicator(
                name=item.name,
                value=round(item.value, parameters.score_decimals),
                weight=round(weights.get(item.name, item.weight), parameters.score_decimals),
                multiplier=round(item.multiplier, parameters.score_decimals),
                shortfall_share=round(item.shortfall_share, parameters.score_decimals),
                diagnostics=dict(computed[item.name].diagnostics)
                | self._extra_diagnostics(item.name, detection, lifecycle.rule, yoon),
                explanation=computed[item.name].explanation,
            )
            for item in outcome.breakdown
        }
        # Неизмеренный индикатор остаётся в карточке строкой «не измерен». Убрать его значило бы
        # показать пять индикаторов из шести и промолчать о шестом — а пропавшая строка читается
        # как «всё в порядке».
        for name in unmeasured:
            aggregated[name] = ScoredIndicator(
                name=name,
                value=round(computed[name].value, parameters.score_decimals),
                weight=0.0,
                multiplier=1.0,
                shortfall_share=0.0,
                diagnostics=dict(computed[name].diagnostics)
                | self._extra_diagnostics(name, detection, lifecycle.rule, yoon),
                explanation=computed[name].explanation,
                measured=False,
            )
        # Канонический порядок §3 — для шести штатных индикаторов; всё, что добавлено
        # расширением, идёт следом в порядке появления. Фильтровать по канону нельзя: продукт
        # обещает подключаемые индикаторы, и молча потерянный индикатор — это отменённое обещание.
        ordered = [name for name in INDICATOR_NAMES if name in aggregated]
        ordered.extend(name for name in aggregated if name not in INDICATOR_NAMES)
        scored = tuple(aggregated[name] for name in ordered)

        return EmergenceResult(
            trend_key=prepared.topic.key,
            title=prepared.topic.label,
            aliases=prepared.topic.aliases,
            score=outcome.score,
            confidence=round(confidence.value, parameters.score_decimals),
            low_evidence=confidence.value < self.profile.confidence_threshold,
            indicators=scored,
            lifecycle_stage=lifecycle.stage,
            first_mention_year=(
                prepared.first_mention_year
                if prepared.first_mention_year is not None
                else prepared.corpus.current_year
            ),
            total_documents=prepared.topic.document_frequency,
            series=prepared.series,
            dov=yoon.dov,
            dod=yoon.dod,
            burst=detection.burst,
            confidence_diagnostics=dict(confidence.diagnostics)
            | {"lifecycleRule": lifecycle.rule, "aggregator": self.profile.aggregator}
            | {f"zeroed_{name}": True for name in outcome.zeroed_by},
            confidence_explanation=confidence.explanation,
        )

    @staticmethod
    def _extra_diagnostics(
        name: IndicatorName,
        detection: object,
        lifecycle_rule: str,
        yoon: object,
    ) -> dict[str, float | int | str | bool]:
        """Attach cross-cutting diagnostics to the indicators that own them."""
        from horizon_analytics.domain.scoring.burst import BurstDetection
        from horizon_analytics.domain.scoring.yoon import YoonSeries

        extra: dict[str, float | int | str | bool] = {}
        if name == "growth" and isinstance(detection, BurstDetection):
            extra |= {f"burst_{key}": value for key, value in detection.diagnostics().items()}
            extra["lifecycleRule"] = lifecycle_rule
        if name == "growth" and isinstance(yoon, YoonSeries):
            extra["dovGrowthRate"] = yoon.dov_growth_rate
            extra["dodGrowthRate"] = yoon.dod_growth_rate
            extra["dovMean"] = yoon.dov_mean
            extra["dodMean"] = yoon.dod_mean
            extra["weakSignalQuadrant"] = yoon.is_weak_signal_quadrant
        return extra
