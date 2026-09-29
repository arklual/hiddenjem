"""Role dispatcher: ``python -m horizon_analytics api|worker`` (ADR-0014).

One image, two roles. The role may come from ``argv`` or from ``HORIZON_ANALYTICS_ROLE``;
``argv`` wins so that a compose file can override the image default.
"""

from __future__ import annotations

import asyncio
import sys

from horizon_analytics.config import get_settings
from horizon_analytics.observability import configure_logging, get_logger

__all__ = ["main"]

_LOGGER = get_logger(__name__)

_USAGE = "usage: python -m horizon_analytics [api|worker]"


def main(argv: list[str] | None = None) -> int:
    """Dispatch to the requested role. Returns the process exit code."""
    arguments = sys.argv[1:] if argv is None else argv
    settings = get_settings()
    role = arguments[0] if arguments else settings.role
    configure_logging(level=settings.log_level, json_format=settings.json_logs)

    if role == "api":
        import uvicorn

        uvicorn.run(
            "horizon_analytics.api.main:app",
            host=settings.host,
            port=settings.port,
            log_level=settings.log_level.lower(),
            access_log=False,
        )
        return 0

    if role == "worker":
        from horizon_analytics.worker.main import run_worker

        asyncio.run(run_worker(settings))
        return 0

    _LOGGER.error("startup.unknown_role", role=role)
    sys.stderr.write(f"unknown role {role!r}\n{_USAGE}\n")
    return 2


if __name__ == "__main__":  # pragma: no cover - process entry point
    raise SystemExit(main())
