"""``python -m horizon_analytics.worker`` — the command in ``docker-entrypoint.sh``."""

from __future__ import annotations

from horizon_analytics.worker.main import main

if __name__ == "__main__":  # pragma: no cover - process entry point
    main()
