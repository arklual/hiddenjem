"""Adapters — every piece of I/O in the service lives under this package.

Nothing here is imported by :mod:`horizon_analytics.domain`; the dependency arrow always
points inwards. Each adapter implements one protocol from
:mod:`horizon_analytics.domain.ports`.
"""

from __future__ import annotations
