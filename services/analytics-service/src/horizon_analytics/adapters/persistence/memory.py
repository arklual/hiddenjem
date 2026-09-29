"""In-memory implementations of every port.

Two consumers, one implementation:

* unit and golden tests, which must run with no infrastructure at all;
* the ``HORIZON_MENTION_STORE=memory`` development mode, which lets a laptop run the whole
  service (``up-min`` profile) without ClickHouse.

Because it is real production code rather than a test double, it is held to the same
determinism standard: every read returns data in an explicit, sorted order.
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from datetime import date, datetime
from typing import Any

from horizon_analytics.domain.models import Document, period_label
from horizon_analytics.domain.ports import (
    AnalysisJobRecord,
    MentionFact,
    ProgressStage,
    SnapshotNotFoundError,
)

__all__ = [
    "InMemoryDocumentRepository",
    "InMemoryJobStore",
    "InMemoryMentionFactStore",
    "InMemoryProgressReporter",
    "InMemoryTrendPublisher",
]


class InMemoryDocumentRepository:
    """Serves snapshots from a dictionary."""

    def __init__(self) -> None:
        """Create an empty repository."""
        self._snapshots: dict[str, tuple[Document, ...]] = {}
        self._windows: dict[str, tuple[date, date]] = {}

    def add_snapshot(
        self,
        snapshot_id: str,
        documents: Sequence[Document],
        *,
        window: tuple[date, date] | None = None,
    ) -> None:
        """Register a snapshot, storing its documents in ``document_id`` order."""
        ordered = tuple(sorted(documents, key=lambda item: item.document_id))
        self._snapshots[snapshot_id] = ordered
        if window is not None:
            self._windows[snapshot_id] = window
        elif ordered:
            dates = [document.published_on for document in ordered]
            self._windows[snapshot_id] = (min(dates), max(dates))
        else:
            today = date.today()
            self._windows[snapshot_id] = (today, today)

    async def load_snapshot(self, snapshot_id: str) -> Sequence[Document]:
        """Return the snapshot's documents, sorted by id."""
        try:
            return self._snapshots[snapshot_id]
        except KeyError as error:
            raise SnapshotNotFoundError(snapshot_id) from error

    async def snapshot_window(self, snapshot_id: str) -> tuple[date, date]:
        """Return the snapshot's collection window."""
        try:
            return self._windows[snapshot_id]
        except KeyError as error:
            raise SnapshotNotFoundError(snapshot_id) from error


class InMemoryMentionFactStore:
    """Keeps mention facts in a list and aggregates them on read."""

    def __init__(self) -> None:
        """Create an empty store."""
        self.facts: list[MentionFact] = []

    async def record_mentions(self, facts: Sequence[MentionFact]) -> int:
        """Append facts; returns the number of rows written."""
        self.facts.extend(facts)
        return len(facts)

    async def period_statistics(
        self, snapshot_id: str, term_normalized: str
    ) -> Mapping[str, tuple[int, int]]:
        """Aggregate ``(df, tf)`` per period for one term."""
        documents: dict[str, set[str]] = {}
        occurrences: dict[str, int] = {}
        for fact in self.facts:
            if fact.snapshot_id != snapshot_id or fact.term_normalized != term_normalized:
                continue
            label = period_label(fact.period_start.year)
            documents.setdefault(label, set()).add(fact.document_id)
            occurrences[label] = occurrences.get(label, 0) + fact.occurrences
        return {
            label: (len(documents[label]), occurrences.get(label, 0)) for label in sorted(documents)
        }


class InMemoryTrendPublisher:
    """Records published messages instead of sending them."""

    def __init__(self) -> None:
        """Create an empty publisher."""
        self.analyzed: list[Mapping[str, Any]] = []
        self.failed: list[Mapping[str, Any]] = []
        self.progress: list[Mapping[str, Any]] = []

    async def publish_analyzed(self, payload: Mapping[str, Any], *, correlation_id: str) -> None:
        """Record a ``DomainAnalyzed`` payload."""
        del correlation_id
        self.analyzed.append(payload)

    async def publish_failed(self, payload: Mapping[str, Any], *, correlation_id: str) -> None:
        """Record a ``DomainAnalysisFailed`` payload."""
        del correlation_id
        self.failed.append(payload)

    async def publish_progress(self, payload: Mapping[str, Any], *, correlation_id: str) -> None:
        """Record an ``AnalysisProgressed`` payload."""
        del correlation_id
        self.progress.append(payload)


class InMemoryProgressReporter:
    """Collects progress updates."""

    def __init__(self) -> None:
        """Create an empty reporter."""
        self.events: list[tuple[str, int, str | None]] = []

    async def report(self, stage: ProgressStage, percent: int, message: str | None = None) -> None:
        """Record one progress update."""
        self.events.append((stage, percent, message))


class InMemoryJobStore:
    """Idempotency ledger keyed by ``(researchRequestId, attempt)``."""

    def __init__(self) -> None:
        """Create an empty ledger."""
        self._jobs: dict[tuple[str, int], AnalysisJobRecord] = {}

    async def find(self, research_request_id: str, attempt: int) -> AnalysisJobRecord | None:
        """Return an existing job, if any."""
        return self._jobs.get((research_request_id, attempt))

    async def start(
        self,
        research_request_id: str,
        attempt: int,
        *,
        snapshot_id: str,
        methodology_version: str,
        profile: Mapping[str, Any],
        started_at: datetime,
    ) -> bool:
        """Claim the job; ``False`` when it already exists."""
        del snapshot_id, methodology_version, profile, started_at
        key = (research_request_id, attempt)
        if key in self._jobs:
            return False
        self._jobs[key] = AnalysisJobRecord(
            research_request_id=research_request_id, attempt=attempt, status="RUNNING"
        )
        return True

    async def succeed(
        self,
        research_request_id: str,
        attempt: int,
        *,
        result: Mapping[str, Any],
        stage_timings: Mapping[str, float],
        finished_at: datetime,
    ) -> None:
        """Mark the job as ``SUCCEEDED``."""
        del stage_timings, finished_at
        self._jobs[(research_request_id, attempt)] = AnalysisJobRecord(
            research_request_id=research_request_id,
            attempt=attempt,
            status="SUCCEEDED",
            result=result,
        )

    async def fail(
        self,
        research_request_id: str,
        attempt: int,
        *,
        code: str,
        message: str,
        finished_at: datetime,
    ) -> None:
        """Mark the job as ``FAILED``."""
        del code, message, finished_at
        self._jobs[(research_request_id, attempt)] = AnalysisJobRecord(
            research_request_id=research_request_id, attempt=attempt, status="FAILED"
        )
