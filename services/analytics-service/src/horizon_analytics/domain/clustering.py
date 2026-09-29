"""Deterministic agglomerative clustering — methodology §7 step 5.

Average linkage over cosine distance with a distance threshold of ``0.35``. Agglomerative
clustering is mandated by ADR-0015 precisely because it has no random start: k-means and
HDBSCAN would make the golden snapshots depend on a seed *and* on the library version's
initialisation strategy.

The implementation is a Lance-Williams average-linkage agglomeration written out here
rather than delegated to scikit-learn, for three reasons: the merge order and therefore the
tie-breaking is explicit and auditable; the label numbering is ours, not an implementation
detail of a third-party library; and it removes a version-to-version behaviour risk from the
most determinism-sensitive step of the pipeline.

Complexity is ``O(n²)`` in time and memory; the caller caps ``n`` via
``MethodologyParameters.max_terms_clustered``.
"""

from __future__ import annotations

import numpy as np
import numpy.typing as npt

from horizon_analytics.domain.vectors import Matrix, cosine_matrix

__all__ = ["AgglomerativeResult", "average_linkage_clusters"]


class AgglomerativeResult(tuple[tuple[int, ...], ...]):
    """Clusters as tuples of row indices, ordered by their smallest member index."""

    __slots__ = ()


def average_linkage_clusters(
    vectors: Matrix,
    *,
    distance_threshold: float = 0.35,
) -> AgglomerativeResult:
    """Cluster the rows of ``vectors`` by average-linkage cosine agglomeration.

    Args:
        vectors: ``(n, d)`` matrix whose row order is already deterministic (the caller
            sorts by term key).
        distance_threshold: merging stops once the closest pair of clusters is further
            apart than this. ``0.35`` is the methodology default.

    Returns:
        A tuple of clusters, each a sorted tuple of row indices. Clusters themselves are
        ordered by their smallest member, so the whole result is a pure function of the
        input matrix.

    Determinism notes:
        * the closest pair is selected by ``(distance, i, j)``, so equal distances always
          merge the lowest indices first;
        * distances are rounded to 12 decimals before comparison, which removes the
          "0.34999999999999998 vs 0.35000000000000003" class of platform differences.
    """
    array = np.asarray(vectors, dtype=np.float64)
    if array.ndim != 2 or array.shape[0] == 0:
        return AgglomerativeResult(())
    count = array.shape[0]
    if count == 1:
        return AgglomerativeResult(((0,),))

    distances = np.round(1.0 - cosine_matrix(array), 12)
    np.fill_diagonal(distances, np.inf)

    members: list[list[int]] = [[index] for index in range(count)]
    alive_mask = np.ones(count, dtype=bool)
    sizes = np.ones(count, dtype=np.float64)

    # Nearest-neighbour cache: ``nn_index[i]`` is the smallest-index closest live partner of
    # ``i``, ``nn_distance[i]`` the distance to it. Recomputing only the rows a merge
    # invalidates turns the naive O(n³) scan into roughly O(n²) — which is what makes a
    # 3000-term corpus finish in the analysis budget instead of minutes.
    nn_index = np.zeros(count, dtype=np.int64)
    nn_distance = np.full(count, np.inf, dtype=np.float64)
    for index in range(count):
        _refresh_neighbour(distances, alive_mask, index, nn_index, nn_distance)

    live = count
    while live > 1:
        left = _select_row(alive_mask, nn_index, nn_distance)
        if left < 0 or nn_distance[left] > distance_threshold:
            break
        right = int(nn_index[left])
        if left > right:
            left, right = right, left

        left_size = sizes[left]
        right_size = sizes[right]
        total = left_size + right_size
        updated = np.round(
            (distances[left] * left_size + distances[right] * right_size) / total, 12
        )
        alive_mask[right] = False
        distances[left] = updated
        distances[:, left] = updated
        distances[left, left] = np.inf
        distances[left, right] = np.inf
        distances[right, left] = np.inf
        sizes[left] = total
        members[left] = sorted(members[left] + members[right])
        members[right] = []
        live -= 1

        # Any row whose cached neighbour was one of the merged clusters must be rebuilt;
        # the rest only need to be compared against the new, merged cluster.
        stale = alive_mask & ((nn_index == left) | (nn_index == right))
        stale[left] = True
        # Distinct names for the numpy scalars: reusing the `int` loop variable from above makes
        # the checker widen it to numpy's signedinteger and hides real type mistakes.
        for stale_index in np.flatnonzero(stale):
            _refresh_neighbour(distances, alive_mask, int(stale_index), nn_index, nn_distance)
        for fresh_index in np.flatnonzero(alive_mask & ~stale):
            position = int(fresh_index)
            candidate = float(distances[position, left])
            if candidate < nn_distance[position] or (
                candidate == nn_distance[position] and left < nn_index[position]
            ):
                nn_distance[position] = candidate
                nn_index[position] = left

    clusters = [
        tuple(members[index]) for index in range(count) if alive_mask[index] and members[index]
    ]
    clusters.sort(key=lambda cluster: cluster[0])
    return AgglomerativeResult(tuple(clusters))


def _refresh_neighbour(
    distances: Matrix,
    alive_mask: npt.NDArray[np.bool_],
    index: int,
    nn_index: npt.NDArray[np.int64],
    nn_distance: npt.NDArray[np.float64],
) -> None:
    """Recompute the closest live partner of one row, preferring the smallest index."""
    if not alive_mask[index]:
        nn_distance[index] = np.inf
        return
    row = np.where(alive_mask, distances[index], np.inf)
    row[index] = np.inf
    best = int(np.argmin(row))  # argmin returns the first minimum → smallest index on ties
    nn_index[index] = best
    nn_distance[index] = row[best]


def _select_row(
    alive_mask: npt.NDArray[np.bool_],
    nn_index: npt.NDArray[np.int64],
    nn_distance: npt.NDArray[np.float64],
) -> int:
    """Pick the live row with the smallest neighbour distance, ties by smallest index.

    Equivalent to scanning every pair with the ``(distance, i, j)`` ordering: the winning
    pair's lower index always carries the global minimum in its own cache entry.
    """
    masked = np.where(alive_mask, nn_distance, np.inf)
    if not np.isfinite(masked).any():
        del nn_index
        return -1
    return int(np.argmin(masked))
