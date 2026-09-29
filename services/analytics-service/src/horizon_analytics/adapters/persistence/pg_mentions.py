"""``MentionFactStore`` over PostgreSQL — the fallback for constrained deployments.

Selected with ``HORIZON_MENTION_STORE=postgres`` (the ``up-min`` compose profile). The port
exists exactly so that the domain never learns which store is behind it; the trade-off is
documented in ADR-0007 — Postgres is fine at the scale of a laptop demo and turns into a
heavy ``GROUP BY`` at production volumes.
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from datetime import date

import asyncpg

from horizon_analytics.domain.ports import MentionFact

__all__ = ["TABLE_DDL", "PostgresMentionFactStore"]

TABLE_DDL = """
CREATE TABLE IF NOT EXISTS analytics.term_mentions (
  snapshot_id uuid NOT NULL,
  term_normalized varchar(160) NOT NULL,
  document_id uuid NOT NULL,
  period_start date NOT NULL,
  published_on date NOT NULL,
  source_id varchar(48) NOT NULL,
  source_class varchar(24) NOT NULL,
  organization text NOT NULL DEFAULT '',
  organization_type varchar(24) NOT NULL DEFAULT '',
  country char(2) NULL,
  venue text NOT NULL DEFAULT '',
  citation_count int NOT NULL DEFAULT 0,
  occurrences int NOT NULL DEFAULT 0,
  PRIMARY KEY (snapshot_id, term_normalized, document_id)
);
CREATE INDEX IF NOT EXISTS ix_term_mentions_period
  ON analytics.term_mentions (snapshot_id, term_normalized, period_start);
"""

_INSERT = """
INSERT INTO analytics.term_mentions
  (snapshot_id, term_normalized, document_id, period_start, published_on, source_id,
   source_class, organization, organization_type, country, venue, citation_count, occurrences)
VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13)
ON CONFLICT (snapshot_id, term_normalized, document_id)
DO UPDATE SET occurrences = EXCLUDED.occurrences, citation_count = EXCLUDED.citation_count
"""

_STATS = """
SELECT to_char(period_start, 'YYYY') AS period,
       count(DISTINCT document_id)::int AS df,
       coalesce(sum(occurrences), 0)::int AS tf
  FROM analytics.term_mentions
 WHERE snapshot_id = $1 AND term_normalized = $2
 GROUP BY period
 ORDER BY period
"""


class PostgresMentionFactStore:
    """Mention facts stored in the ``analytics`` schema."""

    def __init__(self, pool: asyncpg.Pool) -> None:
        """Store the connection pool."""
        self._pool = pool

    async def record_mentions(self, facts: Sequence[MentionFact]) -> int:
        """Upsert mention facts in one transaction."""
        if not facts:
            return 0
        rows = [
            (
                fact.snapshot_id,
                fact.term_normalized[:160],
                fact.document_id,
                date(fact.period_start.year, 1, 1),
                fact.published_on,
                fact.source_id[:48],
                fact.source_class[:24],
                fact.organization,
                fact.organization_type[:24],
                (fact.country or None),
                fact.venue,
                max(0, fact.citation_count),
                max(0, fact.occurrences),
            )
            for fact in facts
        ]
        async with self._pool.acquire() as connection, connection.transaction():
            await connection.executemany(_INSERT, rows)
        return len(rows)

    async def period_statistics(
        self, snapshot_id: str, term_normalized: str
    ) -> Mapping[str, tuple[int, int]]:
        """Return ``{period: (df, tf)}`` for one term of one snapshot."""
        async with self._pool.acquire() as connection:
            rows = await connection.fetch(_STATS, snapshot_id, term_normalized)
        return {str(row["period"]): (int(row["df"]), int(row["tf"])) for row in rows}
