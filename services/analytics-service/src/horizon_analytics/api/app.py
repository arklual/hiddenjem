"""FastAPI application for the ``api`` role (ADR-0014).

Endpoints:

* ``GET /health``, ``GET /health/live`` — liveness (the container healthcheck uses the
  first one, see ``docker-entrypoint.sh``);
* ``GET /health/ready`` — readiness: the embedding provider must be constructible;
* ``GET /metrics`` — Prometheus exposition;
* ``POST /internal/analyze`` — debug recomputation, guarded by a shared secret.

The debug endpoint exists so that an operator can reproduce a report against a running
instance without going through Kafka. It is disabled unless
``HORIZON_ANALYTICS_INTERNAL_SECRET`` is set — an unauthenticated compute endpoint would be
a trivial denial-of-service vector.
"""

from __future__ import annotations

import asyncio
import hmac
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Annotated, Any

from fastapi import Depends, FastAPI, Header, HTTPException, Request, Response, status
from fastapi.responses import JSONResponse
from prometheus_client import CONTENT_TYPE_LATEST, generate_latest

from horizon_analytics import METHODOLOGY_VERSION, __version__
from horizon_analytics.api.schemas import (
    AnalyzeRequest,
    AnalyzeResponse,
    ExplainRequest,
    ExplainResponse,
    HealthResponse,
    KnownDirectionsResponse,
    ResolveDirectionRequest,
    ResolveDirectionResponse,
)
from horizon_analytics.application.dto import AnalyzeDomainCommand
from horizon_analytics.config import Settings, get_settings
from horizon_analytics.container import Container, build_container
from horizon_analytics.domain.direction_lexicon import expand_raw, load_direction_lexicon, suggest
from horizon_analytics.domain.extraction.normalization import stem_token
from horizon_analytics.domain.extraction.tokenizer import normalize_text, tokenize
from horizon_analytics.domain.scoring.profile import MethodologyProfile
from horizon_analytics.observability import REGISTRY, configure_logging, get_logger

__all__ = ["app", "create_app"]


def get_container(request: Request) -> Container:
    """Resolve the container built during startup."""
    resolved_container: Container | None = getattr(request.app.state, "container", None)
    if resolved_container is None:  # pragma: no cover - lifespan always sets it
        raise HTTPException(status_code=503, detail="container not initialised")
    return resolved_container


#: Must stay at module level. With ``from __future__ import annotations`` every annotation is a
#: string that FastAPI resolves against the *module* namespace; a dependency alias defined inside
#: ``create_app`` is invisible there, and FastAPI silently falls back to treating the parameter as a
#: required query string. That turns every endpoint using it — including ``/health/ready`` — into a
#: permanent 422, which reads as an unhealthy pod rather than as a bug.
ContainerDep = Annotated[Container, Depends(get_container)]

_LOGGER = get_logger(__name__)


def create_app(settings: Settings | None = None, container: Container | None = None) -> FastAPI:
    """Build the FastAPI application.

    Args:
        settings: configuration override, mostly for tests.
        container: pre-wired collaborators, mostly for tests.
    """
    resolved = settings if settings is not None else get_settings()
    configure_logging(level=resolved.log_level, json_format=resolved.json_logs)

    @asynccontextmanager
    async def lifespan(application: FastAPI) -> AsyncIterator[None]:
        application.state.container = (
            container if container is not None else build_container(resolved)
        )
        _LOGGER.info("api.started", role=resolved.role, version=__version__)
        yield
        _LOGGER.info("api.stopped")

    # Один повтор анализа за раз на процесс — см. ``internal_explain``.
    explain_gate = asyncio.Semaphore(1)
    application = FastAPI(
        title="Horizon analytics service",
        version=__version__,
        summary="Emerging technology trend detection engine",
        lifespan=lifespan,
        docs_url="/internal/docs",
        openapi_url="/internal/openapi.json",
    )

    @application.get("/health", response_model=HealthResponse, tags=["health"])
    @application.get("/health/live", response_model=HealthResponse, tags=["health"])
    async def health_live() -> HealthResponse:
        """Liveness probe: the process is up."""
        return HealthResponse(
            status="ok",
            role=resolved.role,
            version=__version__,
            methodologyVersion=METHODOLOGY_VERSION,
        )

    @application.get("/health/ready", response_model=HealthResponse, tags=["health"])
    async def health_ready(container_dep: ContainerDep) -> HealthResponse:
        """Readiness probe: the embedding provider is constructed and usable."""
        return HealthResponse(
            status="ok",
            role=resolved.role,
            version=__version__,
            methodologyVersion=METHODOLOGY_VERSION,
            embeddingModelId=container_dep.embeddings.model_id,
        )

    @application.get("/metrics", tags=["ops"])
    async def metrics() -> Response:
        """Prometheus exposition of the service's own registry."""
        return Response(content=generate_latest(REGISTRY), media_type=CONTENT_TYPE_LATEST)

    @application.post(
        "/internal/analyze",
        response_model=AnalyzeResponse,
        tags=["internal"],
        status_code=status.HTTP_200_OK,
    )
    async def internal_analyze(
        payload: AnalyzeRequest,
        container_dep: ContainerDep,
        x_internal_token: Annotated[str | None, Header(alias="X-Internal-Token")] = None,
    ) -> AnalyzeResponse:
        """Recompute an analysis synchronously (debugging and support)."""
        _authorize(resolved, x_internal_token)
        body: dict[str, Any] = payload.to_command_dict()
        if "profile" not in body:
            default = MethodologyProfile.default()
            body["profile"] = {
                "profileId": default.profile_id,
                "methodologyVersion": default.methodology_version,
                "aggregator": default.aggregator,
                "weights": dict(default.weights),
                "parameters": {},
                "confidenceThreshold": default.confidence_threshold,
            }
        try:
            # Движок один; прежнее имя ``methodology`` читается как ``signals``, а неизвестное
            # даёт 422 с перечнем известных, а не тихий расчёт умолчанием.
            command = AnalyzeDomainCommand.from_dict(body)
        except ValueError as error:
            raise HTTPException(status_code=422, detail=str(error)) from error
        outcome = await container_dep.use_case().execute(command)
        return AnalyzeResponse(replayed=outcome.replayed, result=dict(outcome.payload))

    @application.get(
        "/internal/directions",
        response_model=KnownDirectionsResponse,
        tags=["internal"],
        status_code=status.HTTP_200_OK,
    )
    async def internal_directions(
        x_internal_token: Annotated[str | None, Header(alias="X-Internal-Token")] = None,
    ) -> KnownDirectionsResponse:
        """Формулировки направлений, которые движок умеет соотнести с корпусом.

        Читает тот же словарь, что и отбор. Второй список, собранный где-то ещё, разошёлся бы с
        первым и начал бы предлагать формулировки, которые на деле не распознаются, — то есть врал
        бы именно в том месте, ради которого заведён.
        """
        _authorize(resolved, x_internal_token)
        # Порядок задан явно: список, меняющий порядок от запуска к запуску, выглядит осмысленным,
        # хотя смысла в этом порядке нет.
        surfaces = sorted(entry.surface for entry in load_direction_lexicon().values())
        return KnownDirectionsResponse(directions=surfaces)

    @application.post(
        "/internal/directions/resolve",
        response_model=ResolveDirectionResponse,
        tags=["internal"],
        status_code=status.HTTP_200_OK,
    )
    async def internal_resolve_direction(
        payload: ResolveDirectionRequest,
        x_internal_token: Annotated[str | None, Header(alias="X-Internal-Token")] = None,
    ) -> ResolveDirectionResponse:
        """Соотнести формулировку направления с предметными кодами корпуса.

        Существует ради шага, который до сих пор пропускался. Словарь применялся на отборе тем — то
        есть уже после сбора, — а сам сбор искал слова русского запроса в англоязычных документах и
        не находил ничего: «искусственный интеллект» заканчивался отказом «не найдено ни одного
        документа» за секунду. Здесь тот же словарь отдаётся раньше, до обращения к источникам.

        Ответственность остаётся у движка: словарь — знание о направлениях, а не о коннекторах, и
        разводить его копии по сервисам значит гарантировать расхождение. Сбор получает готовый
        ответ и ничего про словарь не знает.
        """
        _authorize(resolved, x_internal_token)
        lexicon = load_direction_lexicon()
        words = [stem_token(token.normal) for token in tokenize(normalize_text(payload.query))]
        targets = sorted(expand_raw(words, lexicon))
        return ResolveDirectionResponse(
            targets=targets,
            recognized=bool(targets),
            # Подсказки нужны ровно тогда, когда цели пусты: показывать «может быть, вы имели в
            # виду» рядом с найденным ответом — шум.
            suggestions=[] if targets else list(suggest(words, lexicon)),
        )

    @application.post(
        "/internal/explain",
        response_model=ExplainResponse,
        tags=["internal"],
        status_code=status.HTTP_200_OK,
    )
    async def internal_explain(
        payload: ExplainRequest,
        container_dep: ContainerDep,
        x_internal_token: Annotated[str | None, Header(alias="X-Internal-Token")] = None,
    ) -> ExplainResponse:
        """Explain what happened to specific terms in an analysis.

        Answers "why is this technology not in my report?" by replaying the run with those terms
        watched. Read-only: nothing is published and no job is claimed.
        """
        _authorize(resolved, x_internal_token)
        body: dict[str, Any] = payload.to_command_dict()
        if "profile" not in body:
            default = MethodologyProfile.default()
            body["profile"] = {
                "profileId": default.profile_id,
                "methodologyVersion": default.methodology_version,
                "aggregator": default.aggregator,
                "weights": dict(default.weights),
                "parameters": {},
                "confidenceThreshold": default.confidence_threshold,
            }
        try:
            command = AnalyzeDomainCommand.from_dict(body)
            # Повторы анализа — по одному. Каждый занимает процессор целиком и 2,5–4 ГиБ памяти:
            # два одновременных растягивали друг друга за таймаут вызывающей стороны, три
            # выбивали процесс по памяти (стенд 2026-09-28). Очередь честнее: второй вопрос
            # ждёт, но получает ответ.
            async with explain_gate:
                outcome = await container_dep.explain_use_case().execute(command, tuple(payload.terms))
        except ValueError as error:
            raise HTTPException(status_code=422, detail=str(error)) from error
        rendered = outcome.to_dict()
        return ExplainResponse(stages=rendered["stages"], traces=rendered["traces"])

    @application.exception_handler(HTTPException)
    async def problem_handler(request: Request, exc: HTTPException) -> JSONResponse:
        """Render errors as RFC 9457 problem documents (ADR-0013)."""
        return JSONResponse(
            status_code=exc.status_code,
            media_type="application/problem+json",
            content={
                "type": "about:blank",
                "title": exc.detail if isinstance(exc.detail, str) else "Error",
                "status": exc.status_code,
                "instance": str(request.url.path),
            },
        )

    return application


def _authorize(settings: Settings, token: str | None) -> None:
    """Constant-time check of the shared secret guarding the debug endpoint."""
    if not settings.internal_secret:
        raise HTTPException(status_code=404, detail="internal endpoints are disabled")
    if not token or not hmac.compare_digest(token, settings.internal_secret):
        raise HTTPException(status_code=401, detail="invalid internal token")


app = create_app()
