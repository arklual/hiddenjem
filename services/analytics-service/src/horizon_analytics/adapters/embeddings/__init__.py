"""Pluggable embedding providers (ADR-0009)."""

from __future__ import annotations

__all__ = ["EmbeddingProviderUnavailableError"]


class EmbeddingProviderUnavailableError(RuntimeError):
    """Raised when an optional provider's runtime dependency is missing."""
