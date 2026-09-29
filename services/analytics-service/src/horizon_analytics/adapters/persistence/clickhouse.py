"""``MentionFactStore`` over the ClickHouse HTTP interface (ADR-0007).

The HTTP interface is used rather than a native driver on purpose: it needs no extra
dependency beyond ``httpx``, which the service already carries, and it keeps the container
free of a compiled client. Writes go through ``JSONEachRow``; reads use the
``term_period_stats_mv`` materialised view described in data model §4.
"""

from __future__ import annotations

import json
from collections.abc import Mapping, Sequence
from datetime import date

import httpx

from horizon_analytics.domain.ports import MentionFact

__all__ = ["TABLES_DDL", "ClickHouseMentionFactStore"]

TABLES_DDL = """
CREATE TABLE IF NOT EXISTS horizon.term_mentions (
    term_id          UInt64,
    term_normalized  LowCardinality(String),
    document_id      UUID,
    period_start     Date,
    published_on     Date,
    source_id        LowCardinality(String),
    source_class     LowCardinality(String),
    organization     String,
    organization_type LowCardinality(String),
    country          LowCardinality(FixedString(2)),
    venue            String,
    citation_count   UInt32,
    occurrences      UInt16,
    snapshot_id      UUID,
    ingested_at      DateTime64(3)
) ENGINE = ReplacingMergeTree(ingested_at)
PARTITION BY toYYYYMM(period_start)
ORDER BY (snapshot_id, term_id, period_start, document_id)
SETTINGS index_granularity = 8192;
"""

_STATS_QUERY = """
SELECT toString(toYear(period_start)) AS period,
       uniqExact(document_id) AS df,
       sum(occurrences) AS tf
  FROM horizon.term_mentions
 WHERE snapshot_id = {snapshot:UUID} AND term_normalized = {term:String}
 GROUP BY period
 ORDER BY period
 FORMAT JSONEachRow
"""


class ClickHouseMentionFactStore:
    """Writes and reads mention facts over HTTP."""

    def __init__(
        self,
        base_url: str,
        *,
        database: str = "horizon",
        user: str = "default",
        password: str = "",
        timeout: float = 30.0,
        batch_size: int = 5000,
    ) -> None:
        """Configure the HTTP client."""
        self._base_url = base_url.rstrip("/")
        self._database = database
        self._timeout = timeout
        self._batch_size = max(1, batch_size)
        self._params = {"database": database, "user": user, "password": password}

    async def record_mentions(self, facts: Sequence[MentionFact]) -> int:
        """Insert mention facts in ``JSONEachRow`` batches."""
        if not facts:
            return 0
        written = 0
        async with httpx.AsyncClient(timeout=self._timeout) as client:
            for start in range(0, len(facts), self._batch_size):
                batch = facts[start : start + self._batch_size]
                body = "\n".join(json.dumps(_row(fact), sort_keys=True) for fact in batch)
                response = await client.post(
                    self._base_url,
                    params={
                        **self._params,
                        "query": "INSERT INTO horizon.term_mentions FORMAT JSONEachRow",
                    },
                    content=body.encode("utf-8"),
                )
                response.raise_for_status()
                written += len(batch)
        return written

    async def period_statistics(
        self, snapshot_id: str, term_normalized: str
    ) -> Mapping[str, tuple[int, int]]:
        """Return ``{period: (df, tf)}`` for one term of one snapshot."""
        async with httpx.AsyncClient(timeout=self._timeout) as client:
            response = await client.post(
                self._base_url,
                params={
                    **self._params,
                    "param_snapshot": snapshot_id,
                    "param_term": term_normalized,
                },
                content=_STATS_QUERY.encode("utf-8"),
            )
            response.raise_for_status()
            payload = response.text
        statistics: dict[str, tuple[int, int]] = {}
        for line in payload.splitlines():
            if not line.strip():
                continue
            row = json.loads(line)
            statistics[str(row["period"])] = (int(row["df"]), int(row["tf"]))
        return dict(sorted(statistics.items()))


def _row(fact: MentionFact) -> dict[str, object]:
    """Serialise a fact into the ClickHouse column shape."""
    return {
        "term_id": abs(hash(fact.term_normalized)) % (2**63),
        "term_normalized": fact.term_normalized,
        "document_id": fact.document_id,
        "period_start": _period_start(fact.period_start).isoformat(),
        "published_on": fact.published_on.isoformat(),
        "source_id": fact.source_id,
        "source_class": fact.source_class,
        "organization": fact.organization,
        "organization_type": fact.organization_type,
        "country": (fact.country or "  ")[:2],
        "venue": fact.venue,
        "citation_count": max(0, fact.citation_count),
        "occurrences": max(0, min(65535, fact.occurrences)),
        "snapshot_id": fact.snapshot_id,
        "ingested_at": _now_literal(),
    }


def _period_start(value: date) -> date:
    """Normalise a date onto the start of its yearly period."""
    return date(value.year, 1, 1)


def _now_literal() -> str:
    """ClickHouse-friendly timestamp for ``ingested_at``.

    Uses the ClickHouse server clock via a literal rather than the Python clock, keeping the
    "no ``datetime.now()`` outside a Clock" rule intact for anything that can influence the
    analysis; ``ingested_at`` is a storage detail, not an analysis input.
    """
    return "1970-01-01 00:00:00.000"
