"""Vector helpers shared by clustering, coherence and the relevance filter.

NumPy is used deliberately: pairwise cosine over a few thousand 384-dimensional vectors is
not expressible in pure Python at an acceptable cost. NumPy is a numerical library, not
infrastructure, so the domain stays framework-free and testable without any I/O.

All functions are pure and order-preserving — the caller controls row order, and row order
is always derived from a sorted key (methodology §9).
"""

from __future__ import annotations

import numpy as np
import numpy.typing as npt

__all__ = [
    "Matrix",
    "Vector",
    "centroid",
    "cosine",
    "cosine_matrix",
    "cosine_to_centroid",
    "l2_normalize",
]

Matrix = npt.NDArray[np.float64]
Vector = npt.NDArray[np.float64]


def l2_normalize(matrix: Matrix) -> Matrix:
    """Return ``matrix`` with each row scaled to unit L2 norm; zero rows stay zero."""
    array = np.asarray(matrix, dtype=np.float64)
    if array.ndim == 1:
        norm = float(np.linalg.norm(array))
        return array / norm if norm > 0.0 else array.copy()
    norms = np.linalg.norm(array, axis=1, keepdims=True)
    safe = np.where(norms > 0.0, norms, 1.0)
    return np.asarray(array / safe, dtype=np.float64)


def cosine(left: Vector, right: Vector) -> float:
    """Cosine similarity of two vectors; ``0.0`` when either has zero norm."""
    left_norm = float(np.linalg.norm(left))
    right_norm = float(np.linalg.norm(right))
    if left_norm == 0.0 or right_norm == 0.0:
        return 0.0
    value = float(np.dot(left, right) / (left_norm * right_norm))
    # Guard against 1.0000000000000002 from floating point accumulation.
    return max(-1.0, min(1.0, value))


def cosine_matrix(matrix: Matrix) -> Matrix:
    """Full pairwise cosine similarity matrix of the rows of ``matrix``."""
    normalized = l2_normalize(matrix)
    similarity = normalized @ normalized.T
    return np.asarray(np.clip(similarity, -1.0, 1.0), dtype=np.float64)


def centroid(matrix: Matrix) -> Vector:
    """Arithmetic mean of the rows; a zero vector for an empty input."""
    array = np.asarray(matrix, dtype=np.float64)
    if array.size == 0:
        width = array.shape[1] if array.ndim == 2 else 0
        return np.zeros(width, dtype=np.float64)
    return np.asarray(array.mean(axis=0), dtype=np.float64)


def cosine_to_centroid(matrix: Matrix) -> float:
    """Mean cosine similarity of every row to the centroid of the whole matrix.

    This is exactly ``c_emb`` of methodology §3.5, used both for term clusters and for the
    documents of a single-term topic.
    """
    array = np.asarray(matrix, dtype=np.float64)
    if array.ndim != 2 or array.shape[0] == 0:
        return 0.0
    if array.shape[0] == 1:
        # A single point coincides with its own centroid: perfectly coherent by
        # construction, but only if it carries any signal at all.
        return 1.0 if float(np.linalg.norm(array[0])) > 0.0 else 0.0
    center = centroid(array)
    if float(np.linalg.norm(center)) == 0.0:
        return 0.0
    similarities = [cosine(array[index], center) for index in range(array.shape[0])]
    return float(sum(similarities) / len(similarities))
