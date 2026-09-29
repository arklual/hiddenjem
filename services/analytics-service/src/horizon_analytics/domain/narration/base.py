"""Narrator protocol and the narration request value object (ADR-0010)."""

from __future__ import annotations

from dataclasses import dataclass
from typing import ClassVar, Protocol, runtime_checkable

from horizon_analytics.domain.evidence import EvidenceSelection
from horizon_analytics.domain.models import Motivation, Topic

__all__ = ["NarrationRequest", "TrendNarrator"]


@dataclass(frozen=True, slots=True)
class NarrationRequest:
    """Everything a narrator may look at.

    Deliberately narrow: a narrator receives the topic and its *selected* evidence, so it
    is structurally incapable of citing a document that is not on the card, and it never
    sees the score — narration must not be able to influence ranking.
    """

    topic: Topic
    evidence: EvidenceSelection
    max_sentences: int = 2
    min_term_coverage: float = 0.0


@runtime_checkable
class TrendNarrator(Protocol):
    """Produces the problem/benefit motivation of a trend card."""

    provider: ClassVar[str]

    def narrate(self, request: NarrationRequest) -> Motivation:
        """Build the motivation for one trend."""
        ...
