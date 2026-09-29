"""Horizon analytics service.

Computational core of the Horizon product: term extraction, embeddings, clustering,
emergence indicators, ranking and extractive narration.

Architecture (hexagonal):

* :mod:`horizon_analytics.domain` — framework-free pure logic (no I/O, no web, no broker).
* :mod:`horizon_analytics.application` — use cases wired against domain ports only.
* :mod:`horizon_analytics.adapters` — every piece of I/O (DB, Kafka, HTTP, files).
* :mod:`horizon_analytics.api` / :mod:`horizon_analytics.worker` — the two process roles
  of a single image (ADR-0014).

The single Python → Java contact point is the ``DomainAnalyzed`` event (ADR-0016).
"""

from __future__ import annotations

__all__ = ["METHODOLOGY_VERSION", "__version__"]

__version__ = "1.0.0"

#: Version of the implemented methodology. Emitted with every result (ADR-0015).
#: Any change to a formula, constant or tie-break requires bumping this value.
METHODOLOGY_VERSION = "em-1.0.0"
