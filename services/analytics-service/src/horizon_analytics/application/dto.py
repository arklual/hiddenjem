"""Data transfer objects mirroring ``contracts/schemas/*.json`` one-to-one.

Two rules govern this module.

1. **The contract is the boundary.** Field names here are the JSON names (camelCase); the
   domain never sees them, and the domain's names never leak out.
2. **Everything emitted must validate.** Length caps, enum ranges and the numeric hygiene
   that JSON requires (no ``NaN``/``Infinity``) are applied here, once, on the way out —
   see :func:`_number`.
"""

from __future__ import annotations

import math
from collections.abc import Mapping
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any

from horizon_analytics.application.localization import TrendLocalization
from horizon_analytics.domain.engines import DEFAULT_ENGINE, EngineName, parse_engine
from horizon_analytics.domain.models import (
    INDICATOR_NAMES,
    AggregatorName,
    AnalysisParams,
    IndicatorName,
)
from horizon_analytics.domain.pipeline import PipelineResult
from horizon_analytics.domain.scoring.profile import (
    MethodologyParameters,
    MethodologyProfile,
    unmapped_parameters,
)
from horizon_analytics.domain.scoring.stability import reweighting_scenarios
from horizon_analytics.domain.signal_scoring import SignalScore
from horizon_analytics.observability import get_logger

__all__ = [
    "AnalyzeDomainCommand",
    "Envelope",
    "FailureEvent",
    "ProgressEvent",
    "build_domain_analyzed",
    "envelope",
]

_LOGGER = get_logger(__name__)

_MAX_TREND_KEY = 160
_MAX_TITLE = 200
_MAX_EXPLANATION = 500
_MAX_SOURCE_ID = 48
_MAX_EXTERNAL_ID = 200
_MAX_AUTHORS = 600
_MAX_ORGANIZATION = 400
_MAX_SNIPPET = 1000
_MAX_SUMMARY = 1000
_MAX_EVIDENCE = 20
_MAX_TRENDS = 50


def _number(value: float) -> float | None:
    """Make a float JSON-safe: ``NaN``/``±Infinity`` become ``null``.

    ``json.dumps`` happily emits the literals ``NaN`` and ``Infinity``, which are not valid
    JSON and are rejected by the Java consumer. Diagnostics are the one place where such a
    value can realistically appear (``exp(β)`` for an explosive slope), so it is filtered
    here rather than at every call site.
    """
    if not math.isfinite(value):
        return None
    return value


def _scalar(value: object) -> Any:
    """Recursively coerce a diagnostics value into a JSON-safe scalar."""
    if isinstance(value, (bool, int, str)):
        # ``bool`` is a subclass of ``int`` and must stay a JSON boolean, which it does.
        return value
    if isinstance(value, float):
        return _number(value)
    if isinstance(value, Mapping):
        return {str(key): _scalar(item) for key, item in sorted(value.items())}
    if isinstance(value, (list, tuple)):
        return [_scalar(item) for item in value]
    return str(value)


def _clip(value: str, limit: int) -> str:
    """Truncate a string to ``limit`` characters, preserving at least one character."""
    if len(value) <= limit:
        return value
    return value[: max(1, limit - 1)] + "…"


def _optional_clip(value: str | None, limit: int) -> str | None:
    """:func:`_clip` that passes ``None`` through."""
    return None if value is None else _clip(value, limit)


# ───────────────────────────── inbound: AnalyzeDomain ─────────────────────────────


@dataclass(frozen=True, slots=True)
class AnalyzeDomainCommand:
    """Parsed ``analyze-domain.command.json`` payload."""

    research_request_id: str
    attempt: int
    snapshot_id: str
    normalized_query: str
    params: AnalysisParams
    profile: MethodologyProfile
    query: str | None = None
    #: Каким движком считать. Не параметр методологии, а выбор самого способа считать, поэтому
    #: поле команды наравне с профилем, а не ключ внутри ``parameters``. Движок один, и поле
    #: осталось ради команд прежнего вида: их ``methodology`` читается как ``signals``.
    engine: EngineName = DEFAULT_ENGINE

    @classmethod
    def from_dict(cls, payload: Mapping[str, Any]) -> AnalyzeDomainCommand:
        """Parse and validate a command payload.

        Args:
            payload: тело команды в форме контракта.

        Raises:
            ValueError: when a required field is missing or out of its contract range.
        """
        for required in (
            "researchRequestId",
            "attempt",
            "snapshotId",
            "normalizedQuery",
            "parameters",
            "profile",
        ):
            if required not in payload:
                raise ValueError(f"AnalyzeDomain: missing required field {required!r}")

        raw_parameters = payload["parameters"]
        params = AnalysisParams(
            top_n=int(raw_parameters["topN"]),
            years_window=int(raw_parameters["yearsWindow"]),
            source_classes=tuple(sorted(raw_parameters.get("sourceClasses") or ())),
            min_confidence=(
                float(raw_parameters["minConfidence"])
                if raw_parameters.get("minConfidence") is not None
                else None
            ),
            include_mature=bool(raw_parameters.get("includeMature", False)),
            # Порядок и дубликаты не влияют на результат: множество, а не список. Отсутствие поля
            # означает «пометок нет» — старый отправитель остаётся совместимым.
            suppressed_keys=frozenset(raw_parameters.get("suppressedTrendKeys") or ()),
        )

        raw_profile = payload["profile"]
        weights: dict[IndicatorName, float] = {}
        for name in INDICATOR_NAMES:
            if name in raw_profile["weights"]:
                weights[name] = float(raw_profile["weights"][name])
        aggregator: AggregatorName = raw_profile["aggregator"]
        profile = MethodologyProfile(
            profile_id=str(raw_profile["profileId"]),
            methodology_version=str(raw_profile["methodologyVersion"]),
            aggregator=aggregator,
            weights=weights,
            parameters=MethodologyParameters().with_overrides(raw_profile.get("parameters") or {}),
            confidence_threshold=float(raw_profile["confidenceThreshold"]),
        )
        # Ключ профиля, которого движок не знает, меняет поведение ровно на «ничего», и до этой
        # записи в журнал узнать об этом было нельзя: именно так десять ключей сида не доходили
        # до движка. Не ошибка — оркестратор вправе быть новее, — но видимой она быть обязана.
        ignored = unmapped_parameters(raw_profile.get("parameters") or {})
        if ignored:
            _LOGGER.warning(
                "profile.parameters_ignored", keys=list(ignored), profile_id=profile.profile_id
            )

        return cls(
            research_request_id=str(payload["researchRequestId"]),
            attempt=int(payload["attempt"]),
            snapshot_id=str(payload["snapshotId"]),
            normalized_query=str(payload["normalizedQuery"]),
            params=params,
            profile=profile,
            query=payload.get("query"),
            # Отсутствие поля и выведенный ``methodology`` — «старый отправитель», а неизвестное
            # значение — ошибка команды: разница между «движка не просили» и «просили тот, которого
            # нет».
            engine=parse_engine(payload.get("engine")),
        )

    def to_dict(self) -> dict[str, Any]:
        """Serialise back to the contract shape (used by tests and the debug endpoint)."""
        parameters: dict[str, Any] = {
            "topN": self.params.top_n,
            "yearsWindow": self.params.years_window,
            "includeMature": self.params.include_mature,
        }
        if self.params.source_classes:
            parameters["sourceClasses"] = list(self.params.source_classes)
        if self.params.min_confidence is not None:
            parameters["minConfidence"] = self.params.min_confidence
        if self.params.suppressed_keys:
            # Сортировка обязательна: множество не имеет порядка, а сериализация обязана быть
            # побайтово воспроизводимой (ADR-0015).
            parameters["suppressedTrendKeys"] = sorted(self.params.suppressed_keys)
        payload: dict[str, Any] = {
            "researchRequestId": self.research_request_id,
            "attempt": self.attempt,
            "snapshotId": self.snapshot_id,
            "normalizedQuery": self.normalized_query,
            "engine": self.engine,
            "parameters": parameters,
            "profile": {
                "profileId": self.profile.profile_id,
                "methodologyVersion": self.profile.methodology_version,
                "aggregator": self.profile.aggregator,
                "weights": {name: self.profile.weights[name] for name in INDICATOR_NAMES},
                "parameters": {},
                "confidenceThreshold": self.profile.confidence_threshold,
            },
        }
        if self.query is not None:
            payload["query"] = self.query
        return payload


# ───────────────────────────── outbound: DomainAnalyzed ─────────────────────────────


def _signals_block(score: SignalScore) -> dict[str, Any]:
    """Из чего сложился балл темы у движка внешних признаков.

    Вклады уходят в отчёт целиком, включая нулевые: признак, которого не было, — это ответ на
    вопрос «а почему тут ничего», и убрав его, мы оставили бы читателя гадать, спрашивали ли
    источник вообще. Отличить отсутствие от нуля даёт ``value``: ``null`` — не спросили или не
    ответили, число — наблюдение.
    """
    return {
        "modelVersion": score.model_version,
        "raw": _number(score.raw),
        # Пометка относится к теме целиком: балл собран без внешних данных, по одному корпусу.
        # Без неё две темы с одинаковым баллом — посчитанная по пяти источникам и посчитанная по
        # корпусной частоте — выглядят в отчёте одинаково.
        "external": score.external,
        "missingFeatures": list(score.missing),
        "contributions": [
            {
                "feature": item.name,
                "value": _number(item.value) if item.value is not None else None,
                "normalized": _number(item.normalized),
                "weight": _number(item.weight),
                "contribution": _number(item.contribution),
            }
            for item in score.contributions
        ],
    }


def build_domain_analyzed(
    result: PipelineResult,
    *,
    research_request_id: str,
    attempt: int,
    snapshot_id: str,
    profile: MethodologyProfile,
    localization: Mapping[str, TrendLocalization] | None = None,
    engine: EngineName = DEFAULT_ENGINE,
) -> dict[str, Any]:
    """Map a :class:`PipelineResult` onto the ``DomainAnalyzed`` contract shape.

    Key ordering is fixed by construction so that two runs of the same snapshot serialise
    to identical bytes — the property the golden test asserts.
    """
    # Диапазон места ищется по ключу, а не по позиции: конвейер отдаёт устойчивость только по
    # опубликованным темам, но порядок в двух списках совпасть не обязан, и молчаливое совпадение
    # индексов — обычный источник подмены одной темы другой.
    stability = {row.trend_key: row for row in result.rank_stability}
    # Разложение балла по признакам — только у движка внешних признаков. У методологии его нет и
    # быть не должно: там балл сложен из индикаторов, и они уже лежат в отчёте строка за строкой.
    signals = {row.trend_key: row for row in result.signal_scores}
    proposed = frozenset(result.proposed_keys)
    direction_share = dict(result.direction_share)
    trends: list[dict[str, Any]] = []
    for rank, outcome in enumerate(result.trends[:_MAX_TRENDS], start=1):
        emergence = outcome.result
        indicators = [
            {
                "name": indicator.name,
                "value": _number(indicator.value),
                "weight": _number(indicator.weight),
                "multiplier": _number(indicator.multiplier),
                "shortfallShare": _number(indicator.shortfall_share),
                "explanation": _clip(indicator.explanation, _MAX_EXPLANATION),
                "diagnostics": {
                    str(key): _scalar(value) for key, value in sorted(indicator.diagnostics.items())
                },
            }
            for indicator in emergence.indicators
        ]
        timeline = [
            {
                "period": period,
                "documentCount": emergence.series.df[index],
                "dov": _number(emergence.dov[index]) if index < len(emergence.dov) else None,
                "dod": _number(emergence.dod[index]) if index < len(emergence.dod) else None,
            }
            for index, period in enumerate(emergence.series.periods)
        ]
        evidence = [
            {
                "sourceId": _clip(item.source_id, _MAX_SOURCE_ID),
                "sourceClass": item.source_class,
                "externalId": _optional_clip(item.external_id, _MAX_EXTERNAL_ID),
                "documentId": item.document_id,
                "title": item.title,
                "authors": _optional_clip(item.authors, _MAX_AUTHORS),
                "organization": _optional_clip(item.organization, _MAX_ORGANIZATION),
                "organizationCountry": _optional_clip(item.organization_country, 2),
                "publishedOn": item.published_on.isoformat(),
                "url": item.url,
                "doi": item.doi,
                "citationCount": item.citation_count,
                "relevance": _number(item.relevance),
                "snippet": _optional_clip(item.snippet, _MAX_SNIPPET),
                # Язык оригинала и уровень доверенности — требование ТЗ к каждому источнику
                # («наименование, ссылку, дату публикации, тип источника, язык оригинала и
                # уровень доверенности»). Основание уровня идёт рядом с ним: ярлык без правила
                # читатель проверить не может.
                "language": _optional_clip(item.language, 2),
                "credibility": item.credibility,
                "credibilityBasis": _optional_clip(item.credibility_basis, 200),
                "independent": item.independent,
            }
            for item in outcome.evidence.items[:_MAX_EVIDENCE]
        ]
        evidence_count = len(evidence)
        measured = stability.get(emergence.trend_key)
        trend: dict[str, Any] = {
            "rank": rank,
            **(
                {"rankStability": {"best": measured.best, "worst": measured.worst}}
                if measured is not None
                else {}
            ),
            **(
                {"directionShare": _number(direction_share[emergence.trend_key])}
                if emergence.trend_key in direction_share
                else {}
            ),
            "trendKey": _clip(emergence.trend_key, _MAX_TREND_KEY),
            # Откуда взялось имя темы. Пишется всегда, а не только у предложенных: поле, которое
            # появляется лишь иногда, читается как «здесь что-то особенное», а вопрос «откуда
            # это» задают обо всех темах сразу. У методологии ответ один и тот же — `corpus`.
            "origin": "model" if emergence.trend_key in proposed else "corpus",
            "title": _clip(emergence.title, _MAX_TITLE),
            "definition": outcome.definition,
            "aliases": list(emergence.aliases),
            "motivation": {
                "problem": outcome.motivation.problem,
                "benefit": outcome.motivation.benefit,
                "attributions": [
                    {
                        "statement": attribution.statement,
                        "evidenceIndex": attribution.evidence_index,
                        # Предложение, а не весь абзац: ссылка обязана указывать на свою половину.
                        "sentence": _optional_clip(attribution.sentence, _MAX_SNIPPET),
                    }
                    for attribution in outcome.motivation.attributions
                    if attribution.evidence_index < evidence_count
                ],
            },
            "caseExample": None,
            "score": _number(emergence.score),
            "confidence": _number(emergence.confidence),
            "lowEvidence": emergence.low_evidence,
            "indicators": indicators,
            **(
                {"signals": _signals_block(signals[emergence.trend_key])}
                if emergence.trend_key in signals
                else {}
            ),
            "lifecycleStage": emergence.lifecycle_stage,
            "firstMentionYear": emergence.first_mention_year,
            "totalDocuments": max(1, emergence.total_documents),
            "burst": (
                {
                    "startPeriod": emergence.burst.start_period,
                    "weight": _number(emergence.burst.weight),
                }
                if emergence.burst is not None
                else None
            ),
            "timeline": timeline,
            "evidence": evidence,
            # ТЗ: сведения из медиа и пресс-релизов «не должны быть единственным основанием для
            # включения технологии в итоговую выдачу… либо сопровождаться отметкой о пониженной
            # доверенности». Первую половину закрывает правило хайпа в конвейере, вторую — эта
            # отметка: тема, у которой все источники низкой доверенности, доходит до аналитика с
            # оговоркой, а не молча.
            "lowCredibilityOnly": all(
                item.credibility == "LOW" for item in outcome.evidence.items[:_MAX_EVIDENCE]
            ),
        }
        localized = (localization or {}).get(emergence.trend_key)
        if localized is not None and not localized.empty:
            trend["localization"] = localized.to_dict()
        case = outcome.case_example
        if case is not None and case.evidence_index < evidence_count:
            trend["caseExample"] = {
                "organization": _clip(case.organization, 300),
                "organizationType": case.organization_type,
                "country": _optional_clip(case.country, 2),
                "summary": _optional_clip(case.summary, _MAX_SUMMARY),
                "evidenceIndex": case.evidence_index,
                "basis": case.basis,
            }
        if trend["caseExample"] is not None and trend["caseExample"]["organizationType"] is None:
            del trend["caseExample"]["organizationType"]
        if trend["caseExample"] is not None and trend["caseExample"]["basis"] is None:
            del trend["caseExample"]["basis"]
        # Почему тема — слабый сигнал и почему такая уверенность (ТЗ: «объяснение статуса слабого
        # сигнала, причины присвоения соответствующей уверенности модели»).
        if outcome.explanation:
            trend["explanation"] = [
                {"title": _clip(title, 80), "text": _clip(text, 1200)}
                for title, text in outcome.explanation[:8]
            ]
        trends.append(trend)

    return {
        "researchRequestId": research_request_id,
        "attempt": attempt,
        "snapshotId": snapshot_id,
        # Чем посчитано. Стоит рядом с версией методологии по той же причине: отчёт, который
        # нельзя перечитать зная, чем он получен, — число без происхождения.
        "engine": engine,
        "methodologyVersion": profile.methodology_version,
        "profileId": profile.profile_id,
        "aggregator": profile.aggregator,
        "reweightingScenarios": (
            len(reweighting_scenarios(profile.weights)) + 1 if result.rank_stability else 0
        ),
        "embeddingModelId": result.embedding_model_id,
        "documentsAnalyzed": result.documents_analyzed,
        "candidatesEvaluated": result.candidates_evaluated,
        "truncated": result.truncated,
        # Сколько тем убрано по пометке аналитика. Отбор происходит до ТОП-N, поэтому место занял
        # следующий кандидат — и без этого числа отчёт выглядел бы так, будто скрытого не было
        # вовсе. Молчаливый фильтр производит уверенность вместо сведений.
        "suppressedByAnalyst": int(result.diagnostics.get("suppressed_by_analyst", 0)),
        # Оговорка того же рода, что `partial` у покрытия: относится к отчёту целиком и обязана
        # пережить выгрузку. Отчёт, у которого направление не распознано, ходит по почте наравне с
        # остальными, и различить его получатель может только по этому полю.
        "directionRecognized": result.direction_recognized,
        "directionSuggestions": list(result.direction_suggestions),
        "windowFrom": result.window_from.isoformat(),
        "windowTo": result.window_to.isoformat(),
        "stageTimingsMs": {
            str(key): _number(value) for key, value in sorted(result.stage_timings.items())
        },
        # Логика исключения, которую ТЗ требует показывать: зрелые тренды, массово внедрённые
        # технологии, стандарты, маркетинговый хайп и информационный шум — с числом и примерами.
        # До этого поля отсев существовал только счётчиками в метриках, то есть как число без
        # имён, которое аналитик не может проверить.
        "exclusions": [
            {
                "code": row.code,
                "reason": row.reason,
                "count": row.count,
                "examples": list(row.examples),
            }
            for row in result.exclusions
        ],
        "trends": trends,
    }


# ───────────────────────────── progress & failure ─────────────────────────────


@dataclass(frozen=True, slots=True)
class ProgressEvent:
    """``analysis-progressed.event.json`` payload."""

    research_request_id: str
    attempt: int
    stage: str
    percent: int
    message: str | None = None

    def to_dict(self) -> dict[str, Any]:
        """Serialise to the contract shape."""
        return {
            "researchRequestId": self.research_request_id,
            "attempt": self.attempt,
            "stage": self.stage,
            "percent": max(0, min(100, self.percent)),
            "message": _optional_clip(self.message, 300),
        }


@dataclass(frozen=True, slots=True)
class FailureEvent:
    """``failure.event.json`` payload — published instead of dying silently."""

    research_request_id: str
    attempt: int
    code: str
    message: str
    retryable: bool
    details: Mapping[str, Any] = field(default_factory=dict)

    def to_dict(self) -> dict[str, Any]:
        """Serialise to the contract shape."""
        return {
            "researchRequestId": self.research_request_id,
            "attempt": self.attempt,
            "code": _clip(self.code, 48),
            "message": _clip(self.message, 2000),
            "retryable": self.retryable,
            "details": {str(key): _scalar(value) for key, value in sorted(self.details.items())},
        }


# ───────────────────────────── envelope ─────────────────────────────


@dataclass(frozen=True, slots=True)
class Envelope:
    """``envelope.json`` — the common wrapper of every Horizon message."""

    message_id: str
    type: str
    schema_version: int
    occurred_at: datetime
    source: str
    payload: Mapping[str, Any]
    correlation_id: str | None = None
    causation_id: str | None = None
    traceparent: str | None = None

    def to_dict(self) -> dict[str, Any]:
        """Serialise to the contract shape."""
        return {
            "messageId": self.message_id,
            "type": self.type,
            "schemaVersion": self.schema_version,
            "occurredAt": _isoformat(self.occurred_at),
            "source": self.source,
            "correlationId": self.correlation_id,
            "causationId": self.causation_id,
            "traceparent": self.traceparent,
            "payload": dict(self.payload),
        }

    @classmethod
    def from_dict(cls, payload: Mapping[str, Any]) -> Envelope:
        """Parse an incoming envelope."""
        for required in ("messageId", "type", "schemaVersion", "occurredAt", "source", "payload"):
            if required not in payload:
                raise ValueError(f"Envelope: missing required field {required!r}")
        return cls(
            message_id=str(payload["messageId"]),
            type=str(payload["type"]),
            schema_version=int(payload["schemaVersion"]),
            occurred_at=_parse_datetime(str(payload["occurredAt"])),
            source=str(payload["source"]),
            payload=dict(payload["payload"]),
            correlation_id=payload.get("correlationId"),
            causation_id=payload.get("causationId"),
            traceparent=payload.get("traceparent"),
        )


def _isoformat(moment: datetime) -> str:
    """RFC 3339 timestamp with a ``Z`` suffix, matching the Java side's expectation."""
    text = moment.isoformat()
    return text.replace("+00:00", "Z") if text.endswith("+00:00") else text


def _parse_datetime(value: str) -> datetime:
    """Parse an RFC 3339 timestamp, accepting the ``Z`` suffix."""
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def envelope(
    *,
    message_id: str,
    message_type: str,
    occurred_at: datetime,
    payload: Mapping[str, Any],
    correlation_id: str | None = None,
    causation_id: str | None = None,
    traceparent: str | None = None,
    source: str = "analytics-service",
    schema_version: int = 1,
) -> dict[str, Any]:
    """Build an envelope dictionary ready to be serialised onto Kafka."""
    return Envelope(
        message_id=message_id,
        type=message_type,
        schema_version=schema_version,
        occurred_at=occurred_at,
        source=source,
        payload=payload,
        correlation_id=correlation_id,
        causation_id=causation_id,
        traceparent=traceparent,
    ).to_dict()
