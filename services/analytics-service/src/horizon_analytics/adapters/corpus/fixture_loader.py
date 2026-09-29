"""Loader for the golden corpus JSONL file (ADR-0015).

Each line is a ``document-ingested.event.json`` payload — either bare, or wrapped in an
``envelope.json`` (both shapes are accepted, because the file doubles as a replay log for
the ``fixture`` source connector). Documents are returned sorted by ``documentId``, so the
loader itself can never introduce order-dependence.

The path is ``HORIZON_FIXTURE_CORPUS_PATH``; the repository copy lives in
``fixtures/corpus/documents.jsonl`` and is owned by the fixtures team, not by this service.
"""

from __future__ import annotations

import hashlib
from collections.abc import Iterable, Iterator, Mapping, Sequence
from dataclasses import dataclass
from datetime import UTC, date, datetime
from pathlib import Path
from typing import Any

import orjson

from horizon_analytics.domain.models import (
    Author,
    Document,
    DocumentTopic,
    OrganizationType,
    SourceClass,
    Venue,
)
from horizon_analytics.domain.ports import SnapshotNotFoundError

__all__ = ["FixtureCorpus", "FixtureDocumentRepository", "load_documents", "parse_document"]


def _parse_date(value: str) -> date:
    """Parse an ISO date, tolerating a full timestamp."""
    text = value.strip()
    if "T" in text:
        return datetime.fromisoformat(text.replace("Z", "+00:00")).date()
    return date.fromisoformat(text)


def _parse_datetime(value: str | None) -> datetime:
    """Parse an RFC 3339 timestamp; missing values fall back to the Unix epoch.

    A missing ``fetchedAt`` must not make the corpus depend on the wall clock — the epoch is
    a constant, and this field never enters the analysis.
    """
    if not value:
        return datetime(1970, 1, 1, tzinfo=UTC)
    return datetime.fromisoformat(value.replace("Z", "+00:00"))


def _organization_type(value: Any) -> OrganizationType | None:
    """Narrow the organisation type coming from JSON."""
    if value in {"COMPANY", "UNIVERSITY", "RESEARCH_INSTITUTE", "GOVERNMENT", "NONPROFIT"}:
        return value  # type: ignore[no-any-return]
    return None


def _source_class(value: Any) -> SourceClass:
    """Narrow the source class coming from JSON, defaulting to ``PREPRINT``."""
    if value in {
        "PREPRINT",
        "JOURNAL_ARTICLE",
        "PATENT",
        "CODE_REPOSITORY",
        "NEWS",
        "ANALYST_REPORT",
        "STANDARD",
    }:
        return value  # type: ignore[no-any-return]
    return "PREPRINT"


def parse_document(payload: Mapping[str, Any]) -> Document:
    """Map one ``DocumentIngested`` payload onto the canonical :class:`Document`."""
    venue = payload.get("venue")
    return Document(
        document_id=str(payload["documentId"]),
        source_id=str(payload.get("sourceId") or "fixture"),
        source_class=_source_class(payload.get("sourceClass")),
        external_id=str(payload.get("externalId") or ""),
        title=str(payload.get("title") or ""),
        abstract_text=payload.get("abstractText"),
        language=payload.get("language"),
        published_on=_parse_date(str(payload["publishedOn"])),
        doi=payload.get("doi"),
        arxiv_id=payload.get("arxivId"),
        patent_number=payload.get("patentNumber"),
        url=str(payload.get("url") or ""),
        venue=(
            Venue(
                name=str(venue.get("name") or ""),
                type=venue.get("type"),
                issn=venue.get("issn"),
            )
            if isinstance(venue, Mapping) and venue.get("name")
            else None
        ),
        authors=tuple(
            Author(
                full_name=str(author.get("fullName") or ""),
                orcid=author.get("orcid"),
                organization_name=author.get("organizationName"),
                organization_type=_organization_type(author.get("organizationType")),
                organization_country=author.get("organizationCountry"),
            )
            for author in (payload.get("authors") or [])
        ),
        topics=tuple(
            DocumentTopic(
                code=str(topic.get("code") or ""),
                label=topic.get("label"),
                score=topic.get("score"),
            )
            for topic in (payload.get("topics") or [])
        ),
        citation_count=payload.get("citationCount"),
        extra_metrics={
            str(key): float(value)
            for key, value in sorted((payload.get("extraMetrics") or {}).items())
            if isinstance(value, (int, float))
        },
        dedup_key=str(payload.get("dedupKey") or ""),
        fetched_at=_parse_datetime(payload.get("fetchedAt")),
        relevance=float(payload.get("relevance", 1.0)),
    )


def _iter_payloads(lines: Iterable[str]) -> Iterator[Mapping[str, Any]]:
    """Yield document payloads, unwrapping envelopes when present."""
    for raw in lines:
        line = raw.strip()
        if not line or line.startswith("//"):
            continue
        record = orjson.loads(line)
        if isinstance(record, Mapping) and "payload" in record and "documentId" not in record:
            payload = record["payload"]
            if isinstance(payload, Mapping):
                yield payload
            continue
        if isinstance(record, Mapping):
            yield record


def load_documents(path: str | Path) -> tuple[Document, ...]:
    """Load and sort every document of a JSONL corpus file.

    Raises:
        FileNotFoundError: when the corpus file does not exist.
    """
    file = Path(path)
    if not file.is_file():
        raise FileNotFoundError(f"fixture corpus not found: {file}")
    with file.open("r", encoding="utf-8") as handle:
        documents = [parse_document(payload) for payload in _iter_payloads(handle)]
    return tuple(sorted(documents, key=lambda document: document.document_id))


@dataclass(frozen=True, slots=True)
class FixtureCorpus:
    """A loaded corpus with the content hash that identifies its snapshot."""

    documents: tuple[Document, ...]
    content_hash: str

    @classmethod
    def load(cls, path: str | Path) -> FixtureCorpus:
        """Load a corpus and derive its content hash from the sorted document ids."""
        documents = load_documents(path)
        digest = hashlib.sha256(
            "\n".join(document.document_id for document in documents).encode("utf-8")
        ).hexdigest()
        return cls(documents=documents, content_hash=digest)

    @property
    def window(self) -> tuple[date, date]:
        """Publication window spanned by the corpus."""
        if not self.documents:
            return (date(1970, 1, 1), date(1970, 1, 1))
        dates = [document.published_on for document in self.documents]
        return (min(dates), max(dates))


class FixtureDocumentRepository:
    """:class:`DocumentRepository` backed by a JSONL corpus file.

    Every snapshot id resolves to the same corpus, which is exactly the property ADR-0015
    wants: the ``fixture`` connector makes an end-to-end run against the real stack
    deterministic without any mocking.
    """

    def __init__(self, path: str | Path) -> None:
        """Load the corpus eagerly so that a missing file fails at wiring time."""
        self._corpus = FixtureCorpus.load(path)

    @property
    def corpus(self) -> FixtureCorpus:
        """The loaded corpus."""
        return self._corpus

    async def load_snapshot(self, snapshot_id: str) -> Sequence[Document]:
        """Return every document of the fixture corpus."""
        if not self._corpus.documents:
            raise SnapshotNotFoundError(snapshot_id)
        return self._corpus.documents

    async def snapshot_window(self, snapshot_id: str) -> tuple[date, date]:
        """Return the corpus publication window."""
        del snapshot_id
        return self._corpus.window
