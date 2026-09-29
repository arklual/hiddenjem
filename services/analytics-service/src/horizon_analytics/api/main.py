"""ASGI entry point referenced by ``docker-entrypoint.sh``.

``uvicorn horizon_analytics.api.main:app`` — the module exists so that the container
contract stays stable even if the application factory moves.
"""

from __future__ import annotations

from horizon_analytics.api.app import app, create_app

__all__ = ["app", "create_app"]
