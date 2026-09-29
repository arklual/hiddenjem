"""Ordered OpenAlex credentials, shared by the signals and maturity clients."""

from __future__ import annotations

import os
import threading
from datetime import UTC, datetime


class OpenAlexKeys:
    """Keep one active credential per process until it reaches its daily limit."""

    def __init__(self) -> None:
        """Create a ring that refreshes from environment when used."""
        self._lock = threading.Lock()
        self._keys: tuple[str, ...] = ()
        self._index = 0
        self._day = datetime.now(UTC).date()

    def current(self, primary: str = "") -> str:
        """Return the active key, with ``primary`` before additional keys."""
        with self._lock:
            self._refresh(primary)
            return self._keys[self._index] if self._keys else ""

    def exhausted(self, key: str, primary: str = "") -> bool:
        """Advance only if this is still the active key; return whether another exists."""
        with self._lock:
            self._refresh(primary)
            if not self._keys:
                return False
            if key != self._keys[self._index]:
                return True
            if self._index + 1 >= len(self._keys):
                return False
            self._index += 1
            return True

    def _refresh(self, primary: str) -> None:
        today = datetime.now(UTC).date()
        if today != self._day:
            self._day = today
            self._index = 0
        values = [primary or os.environ.get("HORIZON_OPENALEX_API_KEY", "")]
        values.extend(os.environ.get("HORIZON_OPENALEX_API_KEYS", "").split(","))
        keys = tuple(dict.fromkeys(value.strip() for value in values if value.strip()))
        if keys != self._keys:
            self._keys = keys
            self._index = 0


keys = OpenAlexKeys()
