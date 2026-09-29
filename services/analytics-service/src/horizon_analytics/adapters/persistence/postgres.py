"""PostgreSQL adapter over the ``analytics`` schema (data model §4).

Implements :class:`DocumentRepository` and :class:`AnalysisJobStore`, plus helpers for the
embedding and term-dictionary tables. Every query carries an explicit ``ORDER BY`` — the
single most common way an analytics pipeline stops being reproducible is a ``SELECT``
without one.

The service owns the ``analytics`` schema only. It never reads ``trends`` (ADR-0016).
"""

from __future__ import annotations

import json
from collections.abc import Mapping, Sequence
from datetime import date, datetime, timedelta
from typing import Any

import asyncpg

from horizon_analytics.domain.models import (
    Author,
    Document,
    DocumentTopic,
    OrganizationType,
    SourceClass,
    Venue,
)
from horizon_analytics.domain.ports import AnalysisJobRecord, SnapshotNotFoundError

__all__ = [
    "SCHEMA_DDL",
    "STALE_CLAIM",
    "PostgresAnalysisJobStore",
    "PostgresDocumentRepository",
    "connect",
]

#: Через сколько заявка на задание считается брошенной и может быть перехвачена.
#:
#: Больше любого разумного прогона и больше дедлайна саги: перехватить работающего соседа дороже,
#: чем подождать лишние минуты. Заявка появилась как ответ на наблюдаемый отказ — перезапуск
#: воркера во время анализа оставлял задание занятым навсегда.
STALE_CLAIM = timedelta(minutes=30)

#: Minimal DDL matching ``docs/01-analysis/04-data-model.md`` §4.
#:
#: Применяется воркером при старте — идемпотентно, `CREATE TABLE IF NOT EXISTS`. Подпись
#: «применяется ролью migrate» стояла здесь с самого начала и не соответствовала ничему: ни один
#: код и ни один шаг развёртывания эту DDL не выполнял, и схема движка не существовала ни в одном
#: развёртывании. Обнаружилось это только когда движку впервые дали доступ к базе:
#: `relation "analytics.analysis_jobs" does not exist` при живой и доступной базе.
#:
#: Самой схемы здесь нет намеренно: её создаёт развёртывание (`deploy/compose/initdb/20-schemas.sql`)
#: и делает владельцем роль сервиса. У прикладной роли нет CREATE на базе — это осознанное
#: ограничение прав, и обходить его из приложения нельзя.
SCHEMA_DDL = """
CREATE TABLE IF NOT EXISTS analytics.corpus_snapshots (
  id uuid PRIMARY KEY,
  normalized_query varchar(200) NOT NULL,
  window_from date NOT NULL,
  window_to date NOT NULL,
  document_count int NOT NULL,
  source_ids varchar(48)[] NOT NULL,
  content_hash char(64) NOT NULL,
  created_at timestamptz NOT NULL
);
CREATE INDEX IF NOT EXISTS ix_snapshots_query
  ON analytics.corpus_snapshots (normalized_query, created_at DESC);

CREATE TABLE IF NOT EXISTS analytics.snapshot_documents (
  snapshot_id uuid NOT NULL,
  document_id uuid NOT NULL,
  relevance numeric(7,6) NOT NULL,
  PRIMARY KEY (snapshot_id, document_id)
);

CREATE TABLE IF NOT EXISTS analytics.document_embeddings (
  document_id uuid PRIMARY KEY,
  model_id varchar(64) NOT NULL,
  dim smallint NOT NULL,
  embedding vector(384) NOT NULL,
  computed_at timestamptz NOT NULL
);

CREATE TABLE IF NOT EXISTS analytics.term_dictionary (
  id bigserial PRIMARY KEY,
  normalized varchar(160) NOT NULL,
  surface_forms text[] NOT NULL,
  is_acronym boolean NOT NULL DEFAULT false,
  canonical_id bigint NULL REFERENCES analytics.term_dictionary(id),
  first_seen_year smallint NULL,
  created_at timestamptz NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_terms_normalized
  ON analytics.term_dictionary (normalized);

CREATE TABLE IF NOT EXISTS analytics.term_embeddings (
  term_id bigint PRIMARY KEY REFERENCES analytics.term_dictionary(id),
  model_id varchar(64) NOT NULL,
  embedding vector(384) NOT NULL
);

CREATE TABLE IF NOT EXISTS analytics.analysis_jobs (
  id uuid PRIMARY KEY,
  research_request_id uuid NOT NULL,
  snapshot_id uuid NULL,
  status varchar(16) NOT NULL CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
  methodology_version varchar(24) NOT NULL,
  profile jsonb NOT NULL,
  attempt smallint NOT NULL DEFAULT 1,
  stage_timings jsonb NOT NULL DEFAULT '{}',
  result jsonb NULL,
  error_code varchar(48) NULL,
  error_message text NULL,
  started_at timestamptz NOT NULL,
  finished_at timestamptz NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS ux_jobs_request_attempt
  ON analytics.analysis_jobs (research_request_id, attempt);
"""

_DOCUMENT_QUERY = """
SELECT d.document_id, d.source_id, d.source_class, d.external_id, d.title,
       d.abstract_text, d.language, d.published_on, d.doi, d.arxiv_id, d.patent_number,
       d.url, d.venue, d.authors, d.topics, d.citation_count, d.dedup_key, d.fetched_at,
       sd.relevance
  FROM analytics.snapshot_documents sd
  JOIN ingestion.documents d ON d.document_id = sd.document_id
 WHERE sd.snapshot_id = $1
 ORDER BY d.document_id
"""


async def connect(dsn: str, *, min_size: int = 1, max_size: int = 4) -> asyncpg.Pool:
    """Create an asyncpg pool with a JSON codec registered."""
    return await asyncpg.create_pool(dsn=dsn, min_size=min_size, max_size=max_size)


def _as_source_class(value: str) -> SourceClass:
    """Narrow a database string to the contract enum."""
    return value  # type: ignore[return-value]


def _as_organization_type(value: str | None) -> OrganizationType | None:
    """Narrow a database string to the contract enum."""
    return value  # type: ignore[return-value]


def _row_to_document(row: Mapping[str, Any]) -> Document:
    """Map one database row onto the canonical :class:`Document`."""
    venue_raw = row.get("venue")
    venue_data = json.loads(venue_raw) if isinstance(venue_raw, str) else venue_raw
    authors_raw = row.get("authors")
    authors_data = json.loads(authors_raw) if isinstance(authors_raw, str) else (authors_raw or [])
    topics_raw = row.get("topics")
    topics_data = json.loads(topics_raw) if isinstance(topics_raw, str) else (topics_raw or [])

    return Document(
        document_id=str(row["document_id"]),
        source_id=str(row["source_id"]),
        source_class=_as_source_class(str(row["source_class"])),
        external_id=str(row.get("external_id") or ""),
        title=str(row.get("title") or ""),
        abstract_text=row.get("abstract_text"),
        language=row.get("language"),
        published_on=row["published_on"],
        doi=row.get("doi"),
        arxiv_id=row.get("arxiv_id"),
        patent_number=row.get("patent_number"),
        url=str(row.get("url") or ""),
        venue=(
            Venue(
                name=str(venue_data.get("name", "")),
                type=venue_data.get("type"),
                issn=venue_data.get("issn"),
            )
            if venue_data
            else None
        ),
        authors=tuple(
            Author(
                full_name=str(item.get("fullName", "")),
                orcid=item.get("orcid"),
                organization_name=item.get("organizationName"),
                organization_type=_as_organization_type(item.get("organizationType")),
                organization_country=item.get("organizationCountry"),
            )
            for item in authors_data
        ),
        topics=tuple(
            DocumentTopic(
                code=str(item.get("code", "")),
                label=item.get("label"),
                score=item.get("score"),
            )
            for item in topics_data
        ),
        citation_count=row.get("citation_count"),
        dedup_key=str(row.get("dedup_key") or ""),
        fetched_at=row["fetched_at"],
        relevance=float(row.get("relevance") or 1.0),
    )


class PostgresDocumentRepository:
    """Reads corpus snapshots from PostgreSQL."""

    def __init__(self, pool: asyncpg.Pool) -> None:
        """Store the connection pool."""
        self._pool = pool

    async def load_snapshot(self, snapshot_id: str) -> Sequence[Document]:
        """Load every document of a snapshot, ordered by ``document_id``."""
        async with self._pool.acquire() as connection:
            rows = await connection.fetch(_DOCUMENT_QUERY, snapshot_id)
        if not rows:
            exists = await self._snapshot_exists(snapshot_id)
            if not exists:
                raise SnapshotNotFoundError(snapshot_id)
        return [_row_to_document(dict(row)) for row in rows]

    async def snapshot_window(self, snapshot_id: str) -> tuple[date, date]:
        """Return ``(window_from, window_to)`` of the snapshot."""
        async with self._pool.acquire() as connection:
            row = await connection.fetchrow(
                "SELECT window_from, window_to FROM analytics.corpus_snapshots WHERE id = $1",
                snapshot_id,
            )
        if row is None:
            raise SnapshotNotFoundError(snapshot_id)
        return row["window_from"], row["window_to"]

    async def _snapshot_exists(self, snapshot_id: str) -> bool:
        """Whether the snapshot row exists at all."""
        async with self._pool.acquire() as connection:
            row = await connection.fetchrow(
                "SELECT 1 FROM analytics.corpus_snapshots WHERE id = $1", snapshot_id
            )
        return row is not None


class PostgresAnalysisJobStore:
    """Idempotency ledger backed by ``analytics.analysis_jobs``."""

    def __init__(self, pool: asyncpg.Pool) -> None:
        """Store the connection pool."""
        self._pool = pool

    async def find(self, research_request_id: str, attempt: int) -> AnalysisJobRecord | None:
        """Return an existing job for this ``(request, attempt)``."""
        async with self._pool.acquire() as connection:
            row = await connection.fetchrow(
                "SELECT status, result FROM analytics.analysis_jobs "
                "WHERE research_request_id = $1 AND attempt = $2",
                research_request_id,
                attempt,
            )
        if row is None:
            return None
        raw = row["result"]
        result = json.loads(raw) if isinstance(raw, str) else raw
        return AnalysisJobRecord(
            research_request_id=research_request_id,
            attempt=attempt,
            status=str(row["status"]),
            result=result,
        )

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
        """Claim the job atomically; ``False`` when another worker already has it.

        Заявка перехватывается, если прежний владелец не подал признаков жизни дольше
        :data:`STALE_CLAIM`. Без этого упавший или перезапущенный воркер оставлял задание
        навсегда занятым: повторная доставка того же сообщения видела `RUNNING`, писала
        `analysis.already_running` и ничего не делала, а запрос доживал до таймаута саги.
        Ровно это и случилось на стенде при перезапуске контейнера во время анализа.

        Порог сознательно больше любого разумного прогона: перехватить работающего соседа
        дороже, чем подождать лишние минуты. Атомарность сохраняется — перехват идёт тем же
        `INSERT … ON CONFLICT DO UPDATE` с условием на время, то есть выигрывает ровно один.
        """
        async with self._pool.acquire() as connection:
            row = await connection.fetchrow(
                """
                INSERT INTO analytics.analysis_jobs
                    (id, research_request_id, snapshot_id, status, methodology_version,
                     profile, attempt, started_at)
                VALUES (gen_random_uuid(), $1, $2, 'RUNNING', $3, $4::jsonb, $5, $6)
                ON CONFLICT (research_request_id, attempt) DO UPDATE
                   SET status = 'RUNNING',
                       snapshot_id = EXCLUDED.snapshot_id,
                       started_at = EXCLUDED.started_at
                 WHERE analysis_jobs.status = 'RUNNING'
                   AND analysis_jobs.started_at < EXCLUDED.started_at - $7::interval
                RETURNING id
                """,
                research_request_id,
                snapshot_id,
                methodology_version,
                json.dumps(dict(profile), sort_keys=True),
                attempt,
                started_at,
                STALE_CLAIM,
            )
        return row is not None

    async def succeed(
        self,
        research_request_id: str,
        attempt: int,
        *,
        result: Mapping[str, Any],
        stage_timings: Mapping[str, float],
        finished_at: datetime,
    ) -> None:
        """Mark the job as ``SUCCEEDED`` and persist the published payload."""
        async with self._pool.acquire() as connection:
            await connection.execute(
                """
                UPDATE analytics.analysis_jobs
                   SET status = 'SUCCEEDED', result = $3::jsonb,
                       stage_timings = $4::jsonb, finished_at = $5
                 WHERE research_request_id = $1 AND attempt = $2
                """,
                research_request_id,
                attempt,
                json.dumps(dict(result), sort_keys=True, ensure_ascii=False),
                json.dumps(dict(stage_timings), sort_keys=True),
                finished_at,
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
        async with self._pool.acquire() as connection:
            await connection.execute(
                """
                UPDATE analytics.analysis_jobs
                   SET status = 'FAILED', error_code = $3, error_message = $4, finished_at = $5
                 WHERE research_request_id = $1 AND attempt = $2
                """,
                research_request_id,
                attempt,
                code[:48],
                message[:2000],
                finished_at,
            )
