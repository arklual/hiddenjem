"""Pure domain layer.

Nothing in this package may import a web framework, an ORM, a broker client or perform
I/O. Numerical libraries (``numpy``) are allowed: they are computation, not infrastructure.
Time is obtained exclusively through the injected :class:`~horizon_analytics.domain.ports.Clock`.
"""

from __future__ import annotations
