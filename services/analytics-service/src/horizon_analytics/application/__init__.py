"""Application layer: use cases wired against domain ports only.

Nothing here imports FastAPI, aiokafka, asyncpg or httpx. A use case receives its
collaborators as protocol-typed constructor arguments, which is why the whole layer is
testable with the in-memory adapters and no running infrastructure.
"""

from __future__ import annotations
