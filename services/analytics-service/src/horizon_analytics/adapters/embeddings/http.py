"""External embedding service adapter (ADR-0009).

For deployments that already run a corporate embedding service. The provider is only as
deterministic as the remote model, so the model id returned by the service is stored with
every snapshot — a changed remote model must invalidate the golden baselines rather than
silently shift the results.
"""

from __future__ import annotations

from collections.abc import Sequence

import httpx
import numpy as np

from horizon_analytics.domain.vectors import Matrix, l2_normalize

__all__ = ["HttpEmbeddingProvider"]


class HttpEmbeddingProvider:
    """Calls a remote ``POST {base_url}/embeddings`` endpoint."""

    def __init__(
        self,
        base_url: str,
        *,
        dimension: int = 384,
        model_id: str = "http-remote-v1",
        timeout: float = 30.0,
        batch_size: int = 64,
        api_key: str | None = None,
    ) -> None:
        """Configure the remote provider."""
        self._base_url = base_url.rstrip("/")
        self._dimension = dimension
        self._model_id = model_id
        self._timeout = timeout
        self._batch_size = max(1, batch_size)
        self._headers = {"Authorization": f"Bearer {api_key}"} if api_key else {}

    @property
    def model_id(self) -> str:
        """Identifier reported by (or configured for) the remote service."""
        return self._model_id

    @property
    def dimension(self) -> int:
        """Width of the produced vectors."""
        return self._dimension

    def fit(self, corpus: Sequence[str]) -> None:
        """No-op: the remote model is pretrained."""

    def embed(self, texts: Sequence[str]) -> Matrix:
        """Embed texts in batches, preserving input order."""
        items = list(texts)
        if not items:
            return np.zeros((0, self._dimension), dtype=np.float64)
        rows: list[list[float]] = []
        with httpx.Client(timeout=self._timeout, headers=self._headers) as client:
            for start in range(0, len(items), self._batch_size):
                batch = items[start : start + self._batch_size]
                response = client.post(f"{self._base_url}/embeddings", json={"input": batch})
                response.raise_for_status()
                body = response.json()
                for item in body["data"]:
                    rows.append([float(value) for value in item["embedding"]])
        matrix = np.zeros((len(items), self._dimension), dtype=np.float64)
        for index, row in enumerate(rows[: len(items)]):
            width = min(self._dimension, len(row))
            matrix[index, :width] = row[:width]
        return l2_normalize(matrix)
