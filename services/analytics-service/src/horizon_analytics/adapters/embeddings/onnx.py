"""Optional ONNX Runtime embedding provider (ADR-0009).

``onnxruntime`` is **not** a declared dependency: the default image must stay small and
CPU-only. This adapter therefore imports it lazily and degrades with a clear, actionable
error instead of breaking the import graph of the whole service when the package is absent.

The model id embeds the weights hash, so a silently swapped model can never masquerade as
the same embedding space in stored snapshots.
"""

from __future__ import annotations

import hashlib
from collections.abc import Sequence
from pathlib import Path
from typing import Any

import numpy as np

from horizon_analytics.adapters.embeddings import EmbeddingProviderUnavailableError
from horizon_analytics.domain.vectors import Matrix, l2_normalize

__all__ = ["OnnxEmbeddingProvider", "onnxruntime_available"]


def onnxruntime_available() -> bool:
    """Whether ``onnxruntime`` can be imported in this environment."""
    try:
        import onnxruntime  # noqa: F401
    except ImportError:
        return False
    return True


class OnnxEmbeddingProvider:
    """MiniLM-class sentence encoder executed through ONNX Runtime."""

    def __init__(
        self,
        model_path: str,
        *,
        dimension: int = 384,
        max_length: int = 256,
    ) -> None:
        """Load the model, failing loudly when the optional dependency is missing."""
        self._path = Path(model_path)
        self._dimension = dimension
        self._max_length = max_length
        self._session: Any = None
        self._digest = ""

        if not onnxruntime_available():
            raise EmbeddingProviderUnavailableError(
                "HORIZON_EMBEDDING_PROVIDER=onnx requires the optional 'onnxruntime' package; "
                "install it in the image or fall back to the default 'tfidf-svd' provider"
            )
        if not self._path.is_file():
            raise EmbeddingProviderUnavailableError(f"ONNX model not found: {self._path}")

        import onnxruntime

        options = onnxruntime.SessionOptions()
        options.intra_op_num_threads = 2
        options.inter_op_num_threads = 1
        # Deterministic graph optimisation level: no auto-tuning between runs.
        options.graph_optimization_level = onnxruntime.GraphOptimizationLevel.ORT_ENABLE_BASIC
        self._session = onnxruntime.InferenceSession(
            str(self._path), sess_options=options, providers=["CPUExecutionProvider"]
        )
        self._digest = hashlib.sha256(self._path.read_bytes()).hexdigest()[:12]

    @property
    def model_id(self) -> str:
        """Identifier including the weights digest."""
        return f"onnx-{self._path.stem}-{self._dimension}-{self._digest}"

    @property
    def dimension(self) -> int:
        """Width of the produced vectors."""
        return self._dimension

    def fit(self, corpus: Sequence[str]) -> None:
        """No-op: a pretrained encoder has nothing to fit."""

    def embed(self, texts: Sequence[str]) -> Matrix:
        """Encode texts and mean-pool the token states."""
        items = list(texts)
        if not items or self._session is None:
            return np.zeros((len(items), self._dimension), dtype=np.float64)
        input_ids, attention_mask = self._encode(items)
        outputs = self._session.run(
            None, {"input_ids": input_ids, "attention_mask": attention_mask}
        )
        hidden = np.asarray(outputs[0], dtype=np.float64)
        mask = attention_mask.astype(np.float64)[:, :, None]
        pooled = (hidden * mask).sum(axis=1) / np.clip(mask.sum(axis=1), 1e-9, None)
        return l2_normalize(pooled)

    def _encode(self, texts: Sequence[str]) -> tuple[Any, Any]:
        """Byte-level fallback tokenisation.

        A real deployment ships the model's own vocabulary next to the weights; this
        hash-based encoder keeps the adapter self-contained and deterministic for smoke
        tests without pulling in a tokenizer dependency.
        """
        ids = np.zeros((len(texts), self._max_length), dtype=np.int64)
        mask = np.zeros((len(texts), self._max_length), dtype=np.int64)
        for row, text in enumerate(texts):
            tokens = text.lower().split()[: self._max_length]
            for column, token in enumerate(tokens):
                digest = hashlib.sha256(token.encode("utf-8")).digest()
                ids[row, column] = int.from_bytes(digest[:4], "big") % 30000
                mask[row, column] = 1
        return ids, mask
