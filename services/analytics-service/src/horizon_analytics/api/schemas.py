"""Pydantic models of the HTTP surface.

Only the debug endpoint has a body; everything else is health and metrics. The request
model mirrors ``analyze-domain.command.json`` so that an operator can replay a real command
verbatim against a running instance.
"""

from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field

from horizon_analytics.application.explain_term import MAX_TERMS

__all__ = ["AnalyzeRequest", "AnalyzeResponse", "HealthResponse", "ProblemDetail"]


class HealthResponse(BaseModel):
    """Liveness/readiness answer."""

    status: Literal["ok", "degraded"] = "ok"
    role: str
    version: str
    methodology_version: str = Field(alias="methodologyVersion")
    embedding_model_id: str | None = Field(default=None, alias="embeddingModelId")

    model_config = ConfigDict(populate_by_name=True)


class ProblemDetail(BaseModel):
    """RFC 9457 problem document (ADR-0013)."""

    type: str = "about:blank"
    title: str
    status: int
    detail: str | None = None
    instance: str | None = None


class AnalyzeParameters(BaseModel):
    """``parameters`` object of the command."""

    top_n: int = Field(default=15, ge=5, le=50, alias="topN")
    years_window: int = Field(default=7, ge=3, le=15, alias="yearsWindow")
    source_classes: list[str] = Field(default_factory=list, alias="sourceClasses")
    min_confidence: float | None = Field(default=None, ge=0.0, le=1.0, alias="minConfidence")
    include_mature: bool = Field(default=False, alias="includeMature")
    #: Темы, помеченные аналитиками организации как «не технология».
    #:
    #: Поле обязано существовать и здесь, а не только в разборе команды: HTTP-слой имеет свою
    #: модель и молча отбрасывает всё, чего в ней нет. Первая версия правки этого не учла, и
    #: пометка не доходила до конвейера — поймано пробой поведением, а не тестом.
    suppressed_trend_keys: list[str] = Field(
        default_factory=list, alias="suppressedTrendKeys", max_length=200
    )

    model_config = ConfigDict(populate_by_name=True)


class AnalyzeProfile(BaseModel):
    """``profile`` object of the command."""

    profile_id: str = Field(alias="profileId")
    methodology_version: str = Field(alias="methodologyVersion")
    aggregator: Literal["WEIGHTED_GEOMETRIC", "WEIGHTED_ARITHMETIC", "MIN_BOUND"]
    weights: dict[str, float]
    parameters: dict[str, Any] = Field(default_factory=dict)
    confidence_threshold: float = Field(ge=0.0, le=1.0, alias="confidenceThreshold")

    model_config = ConfigDict(populate_by_name=True)


class AnalyzeRequest(BaseModel):
    """Debug analysis request — the ``AnalyzeDomain`` command over HTTP."""

    research_request_id: str = Field(alias="researchRequestId")
    attempt: int = Field(default=1, ge=1)
    snapshot_id: str = Field(alias="snapshotId")
    query: str | None = None
    normalized_query: str = Field(alias="normalizedQuery")
    #: Каким движком считать. Движок один — ``signals``; прежнее ``methodology`` принимается и
    #: читается как ``signals``.
    #:
    #: Обычная строка, а не ``Literal``, намеренно: разбор имени принадлежит домену, и ответ на
    #: незнакомое имя обязан называть известные — сообщение pydantic о нарушении литерала этого
    #: не делает и приходит в чужой форме. Отсутствие означает ``signals``.
    engine: str | None = None
    parameters: AnalyzeParameters = Field(default_factory=AnalyzeParameters)
    profile: AnalyzeProfile | None = None

    model_config = ConfigDict(populate_by_name=True)

    def to_command_dict(self) -> dict[str, Any]:
        """Render the contract-shaped payload the application layer parses."""
        payload: dict[str, Any] = {
            "researchRequestId": self.research_request_id,
            "attempt": self.attempt,
            "snapshotId": self.snapshot_id,
            "normalizedQuery": self.normalized_query,
            "parameters": self.parameters.model_dump(by_alias=True, exclude_none=True),
        }
        if self.query is not None:
            payload["query"] = self.query
        if self.engine is not None:
            payload["engine"] = self.engine
        if self.profile is not None:
            payload["profile"] = self.profile.model_dump(by_alias=True)
        return payload


class ExplainRequest(AnalyzeRequest):
    """Ask what happened to specific terms in an analysis.

    Extends the analysis request rather than inventing a parallel shape: the explanation is only
    meaningful for the exact run it describes, so it needs the same snapshot, query, parameters and
    profile. Any field that could differ is a field that could answer a different question.
    """

    terms: list[str] = Field(min_length=1, max_length=MAX_TERMS)


class KnownDirectionsResponse(BaseModel):
    """Формулировки направлений, которые движок умеет соотнести с корпусом.

    Существует ради самого трудного момента аналитика — пустого поля ввода. Система знает, какие
    формулировки распознаёт, и до этого списка не показывала их: аналитик гадал, а неудачная догадка
    давала отчёт с оговоркой «направление не распознано».

    Список — подсказка, а не закрытый перечень. Свободный ввод остаётся: продукт обязан принимать
    направления, которых словарь ещё не знает, — для того и заведена очередь пополнения.
    """

    directions: list[str]


class ExplainResponse(BaseModel):
    """What happened to each watched term, and the stage vocabulary needed to read it."""

    stages: list[str]
    traces: list[dict[str, Any]]


class AnalyzeResponse(BaseModel):
    """Debug analysis answer: the ``DomainAnalyzed`` payload plus a replay flag."""

    replayed: bool = False
    result: dict[str, Any]


class ResolveDirectionRequest(BaseModel):
    """Направление, которое нужно соотнести со словарём корпуса перед сбором."""

    query: str = Field(min_length=1, max_length=400)


class ResolveDirectionResponse(BaseModel):
    """Предметные коды направления — то, чем размечен корпус.

    Отдаётся сбору, а не отбору тем, и потому цели здесь в исходном виде: сбор ищет слова в тексте
    документов, где они стоят целиком, а не стеммированными. Это и есть недостающее звено между
    русской формулировкой аналитика и англоязычным корпусом: без него запрос «искусственный
    интеллект» не находил ни одного документа, а отчёт по нему заканчивался отказом.

    ``recognized`` отличает «направления нет в словаре» от «словарь ничего не добавил»: первое —
    повод показать подсказки и пополнить словарь, второе бывает у запроса, который и так на языке
    корпуса.
    """

    targets: list[str]
    recognized: bool
    suggestions: list[str] = []
