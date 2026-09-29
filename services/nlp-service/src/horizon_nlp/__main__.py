"""Точка входа: uvicorn на порту из настроек."""

from __future__ import annotations

import uvicorn

from horizon_nlp.config import settings


def main() -> None:
    """Запустить сервис."""
    uvicorn.run(
        "horizon_nlp.api:app",
        host="0.0.0.0",
        port=settings.port,
        log_level=settings.log_level.lower(),
    )


if __name__ == "__main__":
    main()
