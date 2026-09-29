"""Structured logging and Prometheus metrics (NFR-O1..O5).

Logs are JSON with ``trace_id``/``request_id`` bound to a context variable, so every line
emitted anywhere below a bound scope carries the correlation without threading it through
call signatures. Metrics cover exactly what the methodology document says matters
operationally: stage durations, candidate counts and filter drop-offs.
"""

from __future__ import annotations

import logging
import sys
from collections.abc import Iterator, Mapping
from contextlib import contextmanager
from typing import Any

import structlog
from prometheus_client import CollectorRegistry, Counter, Gauge, Histogram, start_http_server

__all__ = [
    "ANALYSES_FAILED",
    "ANALYSES_STARTED",
    "ANALYSES_SUCCEEDED",
    "CANDIDATES",
    "FILTER_DROPS",
    "REGISTRY",
    "STAGE_DURATION",
    "TRENDS_PUBLISHED",
    "bind_context",
    "configure_logging",
    "get_logger",
    "observe_pipeline",
    "serve_metrics",
    "trace_id_of",
]

REGISTRY = CollectorRegistry()

STAGE_DURATION = Histogram(
    "horizon_analytics_stage_duration_seconds",
    "Wall-clock duration of one analysis stage",
    labelnames=("stage",),
    buckets=(0.01, 0.05, 0.1, 0.5, 1.0, 2.5, 5.0, 10.0, 30.0, 60.0, 120.0),
    registry=REGISTRY,
)

CANDIDATES = Gauge(
    "horizon_analytics_candidates",
    "Candidate counts at each pipeline stage of the most recent run",
    labelnames=("stage",),
    registry=REGISTRY,
)

FILTER_DROPS = Counter(
    "horizon_analytics_filter_drops_total",
    "Candidates dropped, by the rule that dropped them",
    labelnames=("rule",),
    registry=REGISTRY,
)

ANALYSES_STARTED = Counter(
    "horizon_analytics_analyses_started_total",
    "Analysis runs started",
    registry=REGISTRY,
)

ANALYSES_SUCCEEDED = Counter(
    "horizon_analytics_analyses_succeeded_total",
    "Analysis runs that published DomainAnalyzed",
    registry=REGISTRY,
)

ANALYSES_FAILED = Counter(
    "horizon_analytics_analyses_failed_total",
    "Analysis runs that published DomainAnalysisFailed",
    labelnames=("code",),
    registry=REGISTRY,
)

TRENDS_PUBLISHED = Histogram(
    "horizon_analytics_trends_published",
    "Number of trends in a published result",
    buckets=(0, 1, 5, 10, 15, 25, 50),
    registry=REGISTRY,
)

_CONFIGURED = False


def configure_logging(*, level: str = "INFO", json_format: bool = True) -> None:
    """Configure ``structlog`` once per process.

    Args:
        level: root log level name.
        json_format: emit JSON (production) or a human readable console format (local).
    """
    global _CONFIGURED
    if _CONFIGURED:
        return
    logging.basicConfig(
        format="%(message)s", stream=sys.stdout, level=getattr(logging, level.upper(), logging.INFO)
    )
    renderer: Any = (
        structlog.processors.JSONRenderer() if json_format else structlog.dev.ConsoleRenderer()
    )
    structlog.configure(
        processors=[
            structlog.contextvars.merge_contextvars,
            structlog.processors.add_log_level,
            structlog.processors.TimeStamper(fmt="iso", utc=True),
            structlog.processors.StackInfoRenderer(),
            structlog.processors.format_exc_info,
            renderer,
        ],
        wrapper_class=structlog.make_filtering_bound_logger(
            getattr(logging, level.upper(), logging.INFO)
        ),
        logger_factory=structlog.PrintLoggerFactory(file=sys.stdout),
        cache_logger_on_first_use=True,
    )
    _CONFIGURED = True


def get_logger(name: str) -> structlog.stdlib.BoundLogger:
    """Return a bound structlog logger."""
    logger: structlog.stdlib.BoundLogger = structlog.get_logger(name)
    return logger


def trace_id_of(traceparent: str | None) -> str | None:
    """Идентификатор трассы из заголовка W3C `00-<trace-id>-<span-id>-<flags>`.

    В лог должен попадать голый идентификатор: именно по нему Grafana связывает строку лога с
    трассой в Tempo. Раньше сюда клался весь заголовок целиком, и связь не работала бы — значение
    не совпадает ни с чем, что знает Tempo.

    Значение, не похожее на заголовок, возвращается как есть: у совместимости с прежними
    сообщениями цена ниже, чем у потери корреляции.
    """
    if not traceparent:
        return None
    parts = traceparent.split("-")
    if len(parts) == 4 and len(parts[1]) == 32:
        return parts[1]
    return traceparent


@contextmanager
def bind_context(**values: str | int | None) -> Iterator[None]:
    """Bind correlation fields (``trace_id``, ``request_id``, ``attempt``) for a scope."""
    cleaned = {key: value for key, value in sorted(values.items()) if value is not None}
    tokens = structlog.contextvars.bind_contextvars(**cleaned)
    try:
        yield
    finally:
        structlog.contextvars.reset_contextvars(**tokens)


def serve_metrics(port: int) -> None:
    """Поднять слушатель Prometheus для роли, у которой нет HTTP-API.

    У роли `worker` нет FastAPI, а значит и эндпойнта `/metrics`, — и именно она считает самый
    долгий этап конвейера. Prometheus скрёб `analytics-worker:9100` по конфигурации, где рядом
    стоял комментарий «роль поднимает отдельный слушатель prometheus_client», а слушателя не
    существовало: длительности стадий, число кандидатов и отсечения фильтров не собирал никто, и
    панели дашборда по ним оставались пустыми.

    Отказ порта не должен ронять воркер: анализ важнее наблюдаемости за ним. Но и молчать нельзя —
    невидимый воркер выглядит так же, как исправный.
    """
    try:
        start_http_server(port, registry=REGISTRY)
    except OSError as error:  # порт занят или недоступен
        get_logger(__name__).warning("worker.metrics_unavailable", port=port, error=str(error))
    else:
        get_logger(__name__).info("worker.metrics_listening", port=port)


def observe_pipeline(
    stage_timings: Mapping[str, float],
    diagnostics: Mapping[str, int],
    wall_clock_seconds: float | None = None,
) -> None:
    """Publish the pipeline's own counters into Prometheus after a run."""
    for stage, milliseconds in stage_timings.items():
        STAGE_DURATION.labels(stage=stage).observe(milliseconds / 1000.0)
    # Сводная стадия `total`. Панель «Полный цикл, p95» — самое заметное число дашборда — спрашивала
    # именно её, а конвейер размечает только свои шаги, поэтому график был пуст. Квантиль полного
    # цикла нельзя собрать из гистограмм отдельных шагов: квантили не складываются.
    #
    # Сумма шагов — не полный цикл, и это установлено замером: на настоящем корпусе она давала 108 с
    # при 207 с прогона, потому что стадия термхуда не была размечена вовсе. Размётка добавлена, но
    # правило остаётся: сумма покрывает ровно то, что размечено, и любая новая неразмеченная работа
    # снова сделает её меньше правды. Поэтому здесь предпочитается измеренная длительность прогона,
    # если её передали, и сумма — только как запасной вариант.
    if stage_timings:
        total = (
            wall_clock_seconds
            if wall_clock_seconds is not None
            else sum(stage_timings.values()) / 1000.0
        )
        STAGE_DURATION.labels(stage="total").observe(total)
    for key, value in diagnostics.items():
        if key.startswith("candidates") or key in {
            "topics",
            "topics_relevant",
            "topics_credible",
            "ranked_before_screen",
        }:
            CANDIDATES.labels(stage=key).set(value)
        else:
            FILTER_DROPS.labels(rule=key).inc(value)
