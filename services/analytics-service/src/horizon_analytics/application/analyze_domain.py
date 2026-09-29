"""``AnalyzeDomainUseCase`` — the single entry point of the analytics service.

Responsibilities, in order:

1. **Idempotency** by ``(researchRequestId, attempt)``. Kafka is at-least-once (ADR-0002),
   so a redelivery must republish the stored result rather than recompute it. The ledger is
   ``analytics.analysis_jobs`` with its ``UNIQUE (research_request_id, attempt)``.
2. Load the snapshot and derive the analysis window.
3. Run the pure pipeline off the event loop, forwarding ``AnalysisProgressed`` events.
4. Publish ``DomainAnalyzed`` — or ``DomainAnalysisFailed`` on any error, never a silent
   death.
"""

from __future__ import annotations

import asyncio
import time
from collections.abc import Mapping
from dataclasses import dataclass
from datetime import date
from typing import Any
from uuid import NAMESPACE_URL, uuid5

from horizon_analytics.application.dto import (
    AnalyzeDomainCommand,
    FailureEvent,
    ProgressEvent,
    build_domain_analyzed,
)
from horizon_analytics.application.localization import LocalizationService, TrendLocalization
from horizon_analytics.domain.engines import (
    EngineContext,
    SignalsConfiguration,
    build_engine,
)
from horizon_analytics.domain.extraction.corpus_cache import CorpusAnalysisCache
from horizon_analytics.domain.narration.base import TrendNarrator
from horizon_analytics.domain.pipeline import PipelineRequest, PipelineResult
from horizon_analytics.domain.ports import (
    AnalysisJobStore,
    Clock,
    DocumentRepository,
    EmbeddingProvider,
    MaturityProbe,
    ProgressStage,
    SnapshotNotFoundError,
    TechnologyJudge,
    TrendPublisher,
)
from horizon_analytics.observability import (
    ANALYSES_FAILED,
    ANALYSES_STARTED,
    ANALYSES_SUCCEEDED,
    TRENDS_PUBLISHED,
    bind_context,
    get_logger,
    observe_pipeline,
    trace_id_of,
)

__all__ = ["AnalyzeDomainResult", "AnalyzeDomainUseCase"]

_LOGGER = get_logger(__name__)

#: Percentage reported for each pipeline stage, used when the pipeline emits an update.
_STAGE_ORDER: tuple[ProgressStage, ...] = (
    "EXTRACTING",
    "EMBEDDING",
    "CLUSTERING",
    "SCORING",
    "NARRATING",
)


@dataclass(frozen=True, slots=True)
class AnalyzeDomainResult:
    """Outcome of handling one command."""

    payload: Mapping[str, Any]
    replayed: bool = False
    """``True`` when the result came from the idempotency ledger instead of a fresh run."""


class AnalyzeDomainUseCase:
    """Handles one ``AnalyzeDomain`` command end to end."""

    def __init__(
        self,
        *,
        documents: DocumentRepository,
        embeddings: EmbeddingProvider,
        corpus_cache: CorpusAnalysisCache | None = None,
        publisher: TrendPublisher,
        jobs: AnalysisJobStore,
        clock: Clock,
        narrator: TrendNarrator | None = None,
        technology_judge: TechnologyJudge | None = None,
        localization: LocalizationService | None = None,
        maturity_probe: MaturityProbe | None = None,
        signals: SignalsConfiguration | None = None,
    ) -> None:
        """Wire the use case against its ports."""
        self._documents = documents
        self._embeddings = embeddings
        self._corpus_cache = corpus_cache if corpus_cache is not None else CorpusAnalysisCache()
        self._publisher = publisher
        self._jobs = jobs
        self._clock = clock
        self._narrator = narrator
        # Оба сотрудника необязательны, и оба отказывают независимо: без судьи состав отчёта
        # прежний, без локализации в отчёте нет русского слоя. Числа методологии не зависят ни
        # от одного из них (ADR-0017).
        self._judge = technology_judge
        self._localization = localization
        # Внешняя проверка зрелости тоже необязательна: без неё мейнстрим судится по корпусу.
        self._maturity = maturity_probe
        # Настройки второго движка. Пустые — законное состояние: методология их не читает, а
        # запрос `engine=signals` на таком развёртывании получит отказ с названной причиной.
        self._signals = signals if signals is not None else SignalsConfiguration()

    async def execute(
        self, command: AnalyzeDomainCommand, *, traceparent: str | None = None
    ) -> AnalyzeDomainResult:
        """Execute the command, publishing progress, the result or a failure."""
        with bind_context(
            request_id=command.research_request_id,
            attempt=command.attempt,
            trace_id=trace_id_of(traceparent),
            snapshot_id=command.snapshot_id,
        ):
            existing = await self._jobs.find(command.research_request_id, command.attempt)
            if existing is not None and existing.status == "SUCCEEDED" and existing.result:
                _LOGGER.info("analysis.idempotent_replay")
                await self._publisher.publish_analyzed(
                    existing.result, correlation_id=command.research_request_id
                )
                return AnalyzeDomainResult(payload=existing.result, replayed=True)

            claimed = await self._jobs.start(
                command.research_request_id,
                command.attempt,
                snapshot_id=command.snapshot_id,
                methodology_version=command.profile.methodology_version,
                profile={"aggregator": command.profile.aggregator},
                started_at=self._clock.now(),
            )
            if not claimed and existing is not None and existing.status == "RUNNING":
                _LOGGER.warning("analysis.already_running")
                return AnalyzeDomainResult(payload={}, replayed=True)

            ANALYSES_STARTED.inc()
            started = time.perf_counter()
            try:
                payload = await self._run(command)
            except Exception as error:
                await self._fail(command, error)
                raise

            elapsed = time.perf_counter() - started
            await self._jobs.succeed(
                command.research_request_id,
                command.attempt,
                result=payload,
                stage_timings=dict(payload.get("stageTimingsMs", {})),
                finished_at=self._clock.now(),
            )
            await self._publisher.publish_analyzed(
                payload, correlation_id=command.research_request_id
            )
            ANALYSES_SUCCEEDED.inc()
            TRENDS_PUBLISHED.observe(len(payload.get("trends", [])))
            _LOGGER.info(
                "analysis.completed",
                engine=command.engine,
                trends=len(payload.get("trends", [])),
                documents=payload.get("documentsAnalyzed"),
                candidates=payload.get("candidatesEvaluated"),
                elapsed_ms=round(elapsed * 1000.0, 3),
            )
            return AnalyzeDomainResult(payload=payload)

    # ───────────────────────────── internals ─────────────────────────────

    async def _run(self, command: AnalyzeDomainCommand) -> dict[str, Any]:
        """Load the snapshot, run the pipeline off-loop and build the event payload."""
        documents = await self._documents.load_snapshot(command.snapshot_id)
        window_from, window_to = await self._documents.snapshot_window(command.snapshot_id)
        window_from, window_to = self._analysis_window(
            window_from, window_to, command.params.years_window
        )

        loop = asyncio.get_running_loop()
        queue: asyncio.Queue[tuple[ProgressStage, int, str | None] | None] = asyncio.Queue()

        def listener(stage: ProgressStage, percent: int, message: str | None) -> None:
            loop.call_soon_threadsafe(queue.put_nowait, (stage, percent, message))

        drain = asyncio.create_task(self._drain_progress(command, queue))
        # Единственная точка выбора движка в сценарии использования: дальше по коду имя движка
        # встречается только как подпись отчёта, а не как условие.
        engine = build_engine(
            command.engine,
            EngineContext(
                embedding_provider=self._embeddings,
                narrator=self._narrator,
                corpus_cache=self._corpus_cache,
                technology_judge=self._judge,
                maturity_probe=self._maturity,
                signals=self._signals,
            ),
        )
        request = PipelineRequest(
            normalized_query=command.normalized_query,
            query=command.query,
            documents=tuple(documents),
            params=command.params,
            profile=command.profile,
            window_from=window_from,
            window_to=window_to,
            today=self._clock.today(),
        )
        started = time.perf_counter()
        try:
            result: PipelineResult = await asyncio.to_thread(engine.run, request, listener)
        finally:
            await queue.put(None)
            await drain

        # Полный цикл измеряется здесь, а не складывается из стадий: сумма покрывает ровно то, что
        # размечено, и на настоящем корпусе она давала вдвое меньше правды.
        observe_pipeline(result.stage_timings, result.diagnostics, time.perf_counter() - started)

        # Русский слой собирается после ранжирования и вне потока конвейера: он обращается по
        # сети, а конвейер обязан оставаться чистой функцией. Отказ моделей означает отчёт без
        # русского слоя, а не отказ отчёта.
        localization: Mapping[str, TrendLocalization] = {}
        if self._localization is not None and result.trends:
            await self._publish_progress(command, "NARRATING", 95, "перевод выдачи на русский и формулировка трендов")
            try:
                localization = await asyncio.to_thread(
                    self._localization.localize,
                    result,
                    direction=command.query or command.normalized_query,
                )
            # Локализация не влияет на числа: отчёт выходит без русского слоя, но выходит.
            except Exception as error:
                _LOGGER.warning("localization.failed", error=str(error))

        return build_domain_analyzed(
            result,
            research_request_id=command.research_request_id,
            attempt=command.attempt,
            snapshot_id=command.snapshot_id,
            profile=command.profile,
            localization=localization,
            # Подпись берётся у самого движка, а не у команды: заглушка обязана подписываться
            # своим именем, даже когда считает чужим расчётом.
            engine=engine.name,
        )

    async def _publish_progress(
        self, command: AnalyzeDomainCommand, stage: ProgressStage, percent: int, message: str
    ) -> None:
        """Сообщить о стадии, не роняя анализ при недоступном брокере."""
        event = ProgressEvent(
            research_request_id=command.research_request_id,
            attempt=command.attempt,
            stage=stage,
            percent=percent,
            message=message,
        )
        try:
            await self._publisher.publish_progress(
                event.to_dict(), correlation_id=command.research_request_id
            )
        except Exception as error:
            _LOGGER.warning("analysis.progress_publish_failed", error=str(error))

    async def _drain_progress(
        self,
        command: AnalyzeDomainCommand,
        queue: asyncio.Queue[tuple[ProgressStage, int, str | None] | None],
    ) -> None:
        """Forward pipeline progress to Kafka while the pipeline runs in a thread."""
        while True:
            item = await queue.get()
            if item is None:
                return
            stage, percent, message = item
            event = ProgressEvent(
                research_request_id=command.research_request_id,
                attempt=command.attempt,
                stage=stage,
                percent=percent,
                message=message,
            )
            try:
                await self._publisher.publish_progress(
                    event.to_dict(), correlation_id=command.research_request_id
                )
            except Exception as error:
                _LOGGER.warning("analysis.progress_publish_failed", error=str(error))

    async def _fail(self, command: AnalyzeDomainCommand, error: BaseException) -> None:
        """Publish ``DomainAnalysisFailed`` and mark the job as ``FAILED``."""
        code, retryable = _classify(error)
        failure = FailureEvent(
            research_request_id=command.research_request_id,
            attempt=command.attempt,
            code=code,
            message=f"{type(error).__name__}: {error}",
            retryable=retryable,
            details={"snapshotId": command.snapshot_id},
        )
        ANALYSES_FAILED.labels(code=code).inc()
        _LOGGER.error("analysis.failed", code=code, retryable=retryable, error=str(error))
        try:
            await self._jobs.fail(
                command.research_request_id,
                command.attempt,
                code=code,
                message=failure.message,
                finished_at=self._clock.now(),
            )
        finally:
            await self._publisher.publish_failed(
                failure.to_dict(), correlation_id=command.research_request_id
            )

    @staticmethod
    def _analysis_window(
        snapshot_from: date, snapshot_to: date, years_window: int
    ) -> tuple[date, date]:
        """Intersect the snapshot's window with the requested ``yearsWindow``.

        The snapshot says what was *collected*; ``yearsWindow`` says what the analyst wants
        *analysed*. Taking the intersection honours both and keeps the period series inside
        the data that actually exists.
        """
        start_year = snapshot_to.year - years_window + 1
        derived = date(start_year, 1, 1)
        return max(snapshot_from, derived), snapshot_to


def _classify(error: BaseException) -> tuple[str, bool]:
    """Map an exception onto a stable failure code and a retryability flag."""
    if isinstance(error, SnapshotNotFoundError):
        return "SNAPSHOT_NOT_FOUND", False
    if isinstance(error, ValueError):
        return "INVALID_COMMAND", False
    if isinstance(error, (TimeoutError, ConnectionError, OSError)):
        return "INFRASTRUCTURE_ERROR", True
    if isinstance(error, MemoryError):
        return "RESOURCE_EXHAUSTED", True
    return "ANALYSIS_FAILED", True


def deterministic_message_id(*parts: str) -> str:
    """Derive a stable ``messageId`` from the causal parts of a message.

    Using UUIDv5 rather than ``uuid4`` means a redelivered command produces the same
    ``messageId``, so the downstream idempotency check by ``messageId`` (ADR-0003) works
    even across worker restarts.
    """
    return str(uuid5(NAMESPACE_URL, "|".join(parts)))
