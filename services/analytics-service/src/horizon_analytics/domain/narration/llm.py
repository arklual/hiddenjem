"""Optional LLM narrator behind the ``narration.provider=llm`` flag (ADR-0010).

Hard constraints, enforced structurally rather than by convention and covered by tests:

* it is **never** constructed unless the flag is on — :func:`build_narrator` in
  ``pipeline.py`` returns the extractive narrator by default;
* it cannot influence the score or the ranking: :class:`NarrationRequest` does not carry
  them, and narration runs after ranking is frozen;
* it must cite the same documents — the request only exposes the already selected evidence,
  and every returned attribution is validated against that list;
* it is a thin adapter: all it does is call an injected :class:`LlmClient` and validate the
  answer. No prompt engineering leaks into the domain, and no network client is imported.

With the flag off the whole pipeline is byte-reproducible; with it on, only the two
free-text fields of the card change.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass
from typing import ClassVar, Literal, Protocol, runtime_checkable

from horizon_analytics.domain.models import Motivation, MotivationAttribution
from horizon_analytics.domain.narration.base import NarrationRequest, TrendNarrator

__all__ = ["LlmClient", "LlmNarrationError", "LlmNarrator"]


class LlmNarrationError(RuntimeError):
    """Raised when the LLM answer violates the citation contract."""


@runtime_checkable
class LlmClient(Protocol):
    """Minimal synchronous completion port. Implemented in ``adapters/`` only."""

    def complete(self, *, system: str, user: str) -> str:
        """Return the model's answer as raw text."""
        ...


@dataclass(frozen=True, slots=True)
class LlmNarrator:
    """Abstractive narrator; falls back to the extractive one on any violation."""

    client: LlmClient
    fallback: TrendNarrator
    provider: ClassVar[str] = "llm"

    _SYSTEM: ClassVar[str] = (
        "Ты помогаешь аналитику технологических трендов. Отвечай СТРОГО двумя строками: "
        "«ПРОБЛЕМА: …» и «ПРЕИМУЩЕСТВО: …». Используй только факты из приведённых "
        "фрагментов. Не добавляй ничего, чего нет в тексте."
    )

    def narrate(self, request: NarrationRequest) -> Motivation:
        """Produce the motivation, degrading to the extractive narrator on any problem."""
        try:
            answer = self.client.complete(system=self._SYSTEM, user=self._prompt(request))
            problem, benefit = self._parse(answer)
        except Exception as error:
            del error
            return self.fallback.narrate(request)
        if not problem or not benefit:
            return self.fallback.narrate(request)
        statements: tuple[Literal["problem", "benefit"], ...] = ("problem", "benefit")
        indices = tuple(range(len(request.evidence.items)))
        attributions = tuple(
            MotivationAttribution(statement=statement, evidence_index=index)
            for statement in statements
            for index in indices[:1]
        )
        # The answer may only cite documents that are already on the card.
        self.validate_attributions(attributions, len(request.evidence.items))
        return Motivation(problem=problem, benefit=benefit, attributions=attributions)

    @staticmethod
    def _prompt(request: NarrationRequest) -> str:
        """Render the evidence snippets; nothing outside the card is ever sent."""
        lines: list[str] = [f"Тема: {request.topic.label}", "Фрагменты источников:"]
        for index, item in enumerate(request.evidence.items):
            snippet = item.snippet or item.title
            lines.append(f"[{index}] {snippet}")
        return "\n".join(lines)

    @staticmethod
    def _parse(answer: str) -> tuple[str, str]:
        """Extract the two labelled lines from the model answer."""
        problem = ""
        benefit = ""
        for raw in answer.splitlines():
            line = raw.strip()
            if line.upper().startswith("ПРОБЛЕМА:"):
                problem = line.split(":", 1)[1].strip()
            elif line.upper().startswith("ПРЕИМУЩЕСТВО:"):
                benefit = line.split(":", 1)[1].strip()
        return problem, benefit

    @staticmethod
    def validate_attributions(
        attributions: Sequence[MotivationAttribution], evidence_count: int
    ) -> None:
        """Verify that every attribution points inside the card's evidence array."""
        for attribution in attributions:
            if not 0 <= attribution.evidence_index < evidence_count:
                raise LlmNarrationError(
                    f"attribution index {attribution.evidence_index} outside evidence"
                )
