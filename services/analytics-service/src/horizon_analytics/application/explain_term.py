"""Answer "why is this technology not in my report?".

Every ranking product is asked this eventually, and the usual answer — a shrug, or a rewritten
prompt until the term appears — is why analysts stop trusting them. The pipeline already records the
fate of a watched term at every stage it could be removed; this use case makes that record reachable
from outside the process.

It deliberately re-runs the analysis rather than storing every candidate's fate during the original
run. Storing them would mean carrying tens of thousands of records per report for a question that is
asked about two or three terms, and the run is deterministic over a frozen snapshot (ADR-0015): the
replay reaches exactly the same verdict as the run being explained. The snapshot id is therefore the
load-bearing input — explaining against a *different* corpus would answer a question nobody asked.

The use case is read-only by construction: no job claim, no event published, no state touched. A
support question must never be able to disturb the analysis it asks about.
"""

from __future__ import annotations

import asyncio
from dataclasses import dataclass
from typing import Any

from horizon_analytics.application.dto import AnalyzeDomainCommand
from horizon_analytics.domain.engines import (
    EngineContext,
    SignalsConfiguration,
    build_engine,
)
from horizon_analytics.domain.extraction.corpus_cache import CorpusAnalysisCache
from horizon_analytics.domain.pipeline import (
    TRACE_STAGES,
    CandidateTrace,
    PipelineRequest,
)
from horizon_analytics.domain.ports import Clock, DocumentRepository, EmbeddingProvider
from horizon_analytics.observability import bind_context, get_logger

_LOGGER = get_logger(__name__)

#: Terms answered in one request. The bound exists because each request replays a full analysis;
#: without it a single call could ask about a thousand terms and cost as much as a thousand reports.
MAX_TERMS = 5


@dataclass(frozen=True, slots=True)
class ExplainTermResult:
    """Traces for the requested terms, plus the vocabulary needed to read them."""

    traces: tuple[CandidateTrace, ...]
    #: Every stage in pipeline order, so a client can render the journey without hardcoding it and
    #: without silently falling behind when the pipeline gains a stage.
    stages: tuple[str, ...] = TRACE_STAGES

    def to_dict(self) -> dict[str, Any]:
        """Serialise to the wire shape the internal API publishes."""
        return {
            "stages": list(self.stages),
            "traces": [
                {
                    "term": trace.key,
                    "canonicalTerm": trace.canonical_key,
                    "stage": trace.stage,
                    "outcome": trace.outcome,
                    "reason": trace.reason,
                    "detail": dict(trace.detail),
                    "inReport": trace.survived,
                }
                for trace in self.traces
            ],
        }


class ExplainTermUseCase:
    """Replays an analysis with a watch list and returns what happened to those terms."""

    def __init__(
        self,
        *,
        documents: DocumentRepository,
        embeddings: EmbeddingProvider,
        corpus_cache: CorpusAnalysisCache | None = None,
        clock: Clock,
        signals: SignalsConfiguration | None = None,
    ) -> None:
        """Wire the read-only ports the replay needs: corpus, embeddings and the clock."""
        self._documents = documents
        self._embeddings = embeddings
        self._corpus_cache = corpus_cache if corpus_cache is not None else CorpusAnalysisCache()
        self._clock = clock
        # Объяснение обязано повторять тот прогон, который объясняет, — включая движок и его
        # артефакт: объяснить отчёт одного движка расчётом другого значит ответить на вопрос,
        # которого никто не задавал.
        self._signals = signals if signals is not None else SignalsConfiguration()

    async def execute(
        self, command: AnalyzeDomainCommand, terms: tuple[str, ...]
    ) -> ExplainTermResult:
        """Re-run ``command``'s analysis, watching ``terms``.

        Raises:
            ValueError: if no term was given, or more than :data:`MAX_TERMS` were.
        """
        watched = tuple(dict.fromkeys(term.strip().lower() for term in terms if term.strip()))
        if not watched:
            raise ValueError("укажите хотя бы один термин")
        if len(watched) > MAX_TERMS:
            raise ValueError(f"не более {MAX_TERMS} терминов за запрос")

        with bind_context(
            request_id=command.research_request_id,
            attempt=command.attempt,
            snapshot_id=command.snapshot_id,
        ):
            documents = await self._documents.load_snapshot(command.snapshot_id)
            window_from, window_to = await self._documents.snapshot_window(command.snapshot_id)

            request = PipelineRequest(
                normalized_query=command.normalized_query,
                query=command.query,
                documents=tuple(documents),
                params=command.params,
                profile=command.profile,
                window_from=window_from,
                window_to=window_to,
                today=self._clock.today(),
                watch=frozenset(watched),
            )
            # No progress listener: nobody is watching a support query, and emitting progress would
            # publish stage events for a run that is not the report's own.
            #
            # Движок берётся из команды тем же выбором, что и у анализа. Объяснение обязано
            # повторять тот прогон, который объясняет: объяснить отчёт одного движка расчётом
            # другого — это ответить на вопрос, которого никто не задавал.
            engine = build_engine(
                command.engine,
                EngineContext(
                    embedding_provider=self._embeddings,
                    corpus_cache=self._corpus_cache,
                    signals=self._signals,
                ),
            )
            result = await asyncio.to_thread(engine.run, request)

            traces = tuple(result.traces)
            _LOGGER.info("explain.completed", terms=len(watched), traced=len(traces))
            return ExplainTermResult(traces=traces)
