"""Worker role: consume ``AnalyzeDomain``, publish ``DomainAnalyzed`` (ADR-0014).

Guarantees this loop provides:

* **idempotent** per ``(researchRequestId, attempt)`` — enforced by the use case against the
  ``analysis_jobs`` ledger, with a process-local ``messageId`` cache in front of it;
* **progressive** — ``AnalysisProgressed`` is published while the pipeline runs, so the SSE
  stream in the UI keeps moving during a 60-second analysis;
* **loud on failure** — any exception becomes a ``DomainAnalysisFailed`` event; the worker
  never dies silently and never blocks a partition on a poisoned message.
"""

from __future__ import annotations

import asyncio
import contextlib
import signal
from typing import Any

import asyncpg

from horizon_analytics.adapters.messaging.kafka import (
    AnalysisCommandConsumer,
    KafkaTrendPublisher,
    build_producer,
)
from horizon_analytics.adapters.persistence.postgres import (
    SCHEMA_DDL,
    PostgresAnalysisJobStore,
    PostgresDocumentRepository,
)
from horizon_analytics.application.dto import AnalyzeDomainCommand, Envelope
from horizon_analytics.config import Settings, get_settings
from horizon_analytics.container import build_container
from horizon_analytics.observability import (
    bind_context,
    configure_logging,
    get_logger,
    serve_metrics,
    trace_id_of,
)

__all__ = ["main", "run_worker"]

_LOGGER = get_logger(__name__)


async def run_worker(settings: Settings | None = None) -> None:
    """Run the consumer loop until SIGTERM/SIGINT."""
    resolved = settings if settings is not None else get_settings()
    configure_logging(level=resolved.log_level, json_format=resolved.json_logs)
    serve_metrics(resolved.worker_metrics_port)

    pool: asyncpg.Pool | None = None
    documents: Any = None
    jobs: Any = None
    # Репозиторий документов выбирает компоновочный корень: при заданном `HORIZON_INGESTION_URL`
    # корпус приходит по HTTP от сбора. Здесь остаётся только журнал заданий — он в базе движка.
    if resolved.postgres_password:
        # Отказ базы здесь — не повод продолжить. Без репозитория `build_container` подставляет
        # эталонный корпус, который отвечает на любой идентификатор снапшота, и движок начинает
        # анализировать фикстуру вместо собранного: отчёт соберётся, будет выглядеть целым и не
        # будет иметь отношения к тому, что нашёл сбор. Падение громче молчаливой подмены —
        # перезапуск контейнера виден, а подменённый корпус не виден никак.
        pool = await asyncpg.create_pool(dsn=resolved.postgres_dsn, min_size=1, max_size=4)
        async with pool.acquire() as connection:
            # Схема движка создаётся здесь, а не миграцией: у сервиса её нет, а DDL лежал в
            # адаптере с подписью «применяется ролью migrate» — и не применялся никем. На стенде
            # это выглядело как `relation "analytics.analysis_jobs" does not exist` при живой базе.
            await connection.execute(SCHEMA_DDL)
        jobs = PostgresAnalysisJobStore(pool)
        if not resolved.ingestion_url:
            documents = PostgresDocumentRepository(pool)
    else:
        # Пароль не задан — это осознанный режим одного развёртывания: демонстрация на эталонном
        # корпусе без базы (ADR-0015). Но сказать об этом надо вслух: молчание здесь неотличимо
        # от неверной конфигурации, а цена ошибки — отчёт не по тем данным.
        _LOGGER.warning(
            "worker.corpus_from_fixture",
            hint=(
                "ANALYTICS_DB_PASSWORD не задан: анализ пойдёт по эталонному корпусу, "
                "а не по собранным документам"
            ),
        )

    producer = await build_producer(resolved.kafka_bootstrap_servers)
    publisher = KafkaTrendPublisher(producer, topic=resolved.kafka_events_topic)
    container = build_container(resolved, documents=documents, publisher=publisher, jobs=jobs)
    use_case = container.use_case()

    async def handle(message: Envelope) -> None:
        """Parse one envelope and run the analysis."""
        with bind_context(
            trace_id=trace_id_of(message.traceparent),
            message_id=message.message_id,
            correlation_id=message.correlation_id,
        ):
            if not message.type.endswith("AnalyzeDomain"):
                _LOGGER.info("worker.ignored_message", type=message.type)
                return
            command = AnalyzeDomainCommand.from_dict(message.payload)
            await use_case.execute(command, traceparent=message.traceparent)

    consumer = AnalysisCommandConsumer(
        bootstrap_servers=resolved.kafka_bootstrap_servers,
        topic=resolved.kafka_commands_topic,
        group_id=resolved.consumer_group,
        handler=handle,
        concurrency=resolved.worker_concurrency,
        liveness_file=resolved.worker_liveness_file,
        max_poll_interval_ms=resolved.kafka_max_poll_interval_ms,
    )

    loop = asyncio.get_running_loop()
    for signal_number in (signal.SIGTERM, signal.SIGINT):
        with contextlib.suppress(NotImplementedError):
            loop.add_signal_handler(signal_number, lambda: asyncio.create_task(consumer.stop()))

    try:
        await consumer.run()
    finally:
        await producer.stop()
        if pool is not None:
            await pool.close()


def main() -> None:
    """Console entry point for ``python -m horizon_analytics.worker``."""
    asyncio.run(run_worker())


if __name__ == "__main__":  # pragma: no cover - process entry point
    main()
