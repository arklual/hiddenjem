"""Shared fixtures for the whole test suite.

Design rule: unit tests must never touch the filesystem, the network or the clock. Any test
that needs time uses :class:`FixedClock`; any test that needs a corpus builds one from the
factories below, which produce fully deterministic documents.
"""

from __future__ import annotations

import json
from collections.abc import Iterator, Sequence
from datetime import UTC, date, datetime
from pathlib import Path
from typing import Any

import pytest

from horizon_analytics.adapters.clock import FixedClock
from horizon_analytics.domain.models import (
    Author,
    Document,
    Posting,
    SourceClass,
    TermCandidate,
    TimeSeries,
    Topic,
    Venue,
)
from horizon_analytics.domain.scoring.profile import MethodologyProfile

#: Repository root, resolved from this file rather than the working directory.
REPO_ROOT = Path(__file__).resolve().parents[3]

#: The golden corpus owned by the fixtures team. Absent until they generate it.
GOLDEN_CORPUS = REPO_ROOT / "fixtures" / "corpus" / "documents.jsonl"

#: Local stand-in corpus used until the golden one lands.
LOCAL_CORPUS = Path(__file__).resolve().parent / "fixtures" / "mini_corpus.jsonl"

#: Contract schemas the outputs must validate against.
SCHEMA_DIR = REPO_ROOT / "contracts" / "schemas"

FIXED_NOW = datetime(2026, 8, 5, 12, 0, 0, tzinfo=UTC)


def make_document(
    document_id: str,
    *,
    year: int,
    title: str = "Untitled",
    abstract: str | None = None,
    source_class: SourceClass = "PREPRINT",
    organizations: Sequence[str] = ("Acme Research Lab",),
    organization_types: Sequence[str | None] = (),
    citation_count: int | None = None,
    venue: str | None = "Journal of Testing",
    source_id: str = "fixture",
    country: str = "US",
    relevance: float = 1.0,
) -> Document:
    """Build a deterministic document for tests."""
    types = list(organization_types) + [None] * (len(organizations) - len(organization_types))
    authors = tuple(
        Author(
            full_name=f"Author {index + 1}",
            organization_name=name,
            organization_type=types[index],  # type: ignore[arg-type]
            organization_country=country,
        )
        for index, name in enumerate(organizations)
    )
    return Document(
        document_id=document_id,
        source_id=source_id,
        source_class=source_class,
        external_id=f"ext-{document_id}",
        title=title,
        abstract_text=abstract,
        published_on=date(year, 6, 15),
        url=f"https://example.org/{document_id}",
        fetched_at=FIXED_NOW,
        venue=Venue(name=venue) if venue else None,
        authors=authors,
        citation_count=citation_count,
        dedup_key=document_id,
        relevance=relevance,
    )


def make_candidate(
    key: str,
    *,
    documents: Sequence[str],
    occurrences: int = 1,
    termhood: float = 0.5,
    surface: str | None = None,
    token_count: int | None = None,
) -> TermCandidate:
    """Build a term candidate with one posting per document id."""
    return TermCandidate(
        key=key,
        surface=surface or key,
        surface_forms=(surface or key,),
        postings=tuple(
            Posting(document_id=document_id, occurrences=occurrences)
            for document_id in sorted(documents)
        ),
        termhood=termhood,
        token_count=token_count if token_count is not None else len(key.split(" ")),
    )


def make_topic(key: str, *, documents: Sequence[str], label: str | None = None) -> Topic:
    """Build a single-member topic."""
    return Topic(
        key=key,
        label=label or key,
        members=(make_candidate(key, documents=documents),),
    )


def make_series(
    df: Sequence[int],
    *,
    corpus: Sequence[int] | None = None,
    tf: Sequence[int] | None = None,
    start_year: int = 2020,
) -> TimeSeries:
    """Build a time series with sensible corpus defaults."""
    periods = tuple(str(start_year + index) for index in range(len(df)))
    return TimeSeries(
        periods=periods,
        df=tuple(df),
        tf=tuple(tf) if tf is not None else tuple(df),
        corpus_df=tuple(corpus) if corpus is not None else tuple([100] * len(df)),
    )


@pytest.fixture
def clock() -> FixedClock:
    """A clock pinned to 2026-08-05."""
    return FixedClock(instant=FIXED_NOW)


@pytest.fixture
def profile() -> MethodologyProfile:
    """The default ``em-1.0.0`` methodology profile."""
    return MethodologyProfile.default()


@pytest.fixture(scope="session")
def domain_analyzed_schema() -> dict[str, Any]:
    """The ``DomainAnalyzed`` JSON schema."""
    return json.loads((SCHEMA_DIR / "domain-analyzed.event.json").read_text(encoding="utf-8"))


@pytest.fixture(scope="session")
def corpus_path() -> Iterator[Path]:
    """Path of the corpus to analyse: the golden one when present, else the local one.

    The golden corpus at ``fixtures/corpus/documents.jsonl`` is produced by another team.
    Until it exists the suite runs against the small local corpus, and the golden snapshot
    test skips with an explicit message rather than silently passing.
    """
    yield GOLDEN_CORPUS if GOLDEN_CORPUS.is_file() else LOCAL_CORPUS
