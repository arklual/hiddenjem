"""Clock adapters (ADR-0015).

``datetime.now()`` never appears in domain or application code. Production uses
:class:`SystemClock`; deterministic QA pins :class:`FixedClock`, which is also what
``HORIZON_FIXED_CLOCK`` switches on inside a real container so that an E2E run against the
golden corpus produces the same report every time.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, date, datetime

__all__ = ["FixedClock", "SystemClock", "build_clock"]


@dataclass(frozen=True, slots=True)
class SystemClock:
    """Real UTC clock."""

    def now(self) -> datetime:
        """Current UTC instant."""
        return datetime.now(tz=UTC)

    def today(self) -> date:
        """Current UTC date."""
        return self.now().date()


@dataclass(frozen=True, slots=True)
class FixedClock:
    """Clock pinned to a constant instant."""

    instant: datetime

    @classmethod
    def parse(cls, value: str) -> FixedClock:
        """Build from an RFC 3339 string, accepting the ``Z`` suffix."""
        moment = datetime.fromisoformat(value.replace("Z", "+00:00"))
        return cls(instant=moment if moment.tzinfo else moment.replace(tzinfo=UTC))

    def now(self) -> datetime:
        """The pinned instant."""
        return self.instant

    def today(self) -> date:
        """Date of the pinned instant."""
        return self.instant.date()


def build_clock(fixed: str | None) -> SystemClock | FixedClock:
    """Return a fixed clock when ``fixed`` is set, otherwise the system clock."""
    if fixed:
        return FixedClock.parse(fixed)
    return SystemClock()
