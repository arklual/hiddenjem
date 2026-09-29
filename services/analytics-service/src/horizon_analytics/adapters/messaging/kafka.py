"""aiokafka consumer and producer with envelope handling and idempotency (ADR-0002/0003).

Conventions from ``contracts/asyncapi/horizon-events.yaml``:

* every message is wrapped in ``envelope.json``;
* the partition key is ``researchRequestId``, which preserves per-saga ordering;
* delivery is at-least-once, so the consumer deduplicates by ``messageId`` **and** the use
  case is idempotent by ``(researchRequestId, attempt)`` — belt and braces, because the
  ``messageId`` cache is process-local while the job ledger is not.
"""

from __future__ import annotations

import asyncio
from collections import OrderedDict
from collections.abc import Awaitable, Callable, Mapping
from datetime import UTC, datetime
from typing import Any
from uuid import uuid4

import orjson
from aiokafka import AIOKafkaConsumer, AIOKafkaProducer

from horizon_analytics.application.analyze_domain import deterministic_message_id
from horizon_analytics.application.dto import Envelope, envelope
from horizon_analytics.observability import get_logger

__all__ = ["AnalysisCommandConsumer", "KafkaTrendPublisher", "MessageDeduplicator"]

_LOGGER = get_logger(__name__)

MessageHandler = Callable[[Envelope], Awaitable[None]]


def _dumps(payload: Mapping[str, Any]) -> bytes:
    """Serialise a message deterministically (sorted keys, no whitespace drift)."""
    return orjson.dumps(payload, option=orjson.OPT_SORT_KEYS)


class MessageDeduplicator:
    """Bounded LRU of seen ``messageId`` values.

    Process-local by design: the durable idempotency guarantee lives in
    ``analytics.analysis_jobs``. This only spares the service from recomputing an analysis
    when the broker redelivers within the same process lifetime.
    """

    def __init__(self, capacity: int = 10_000) -> None:
        """Create a deduplicator with the given capacity."""
        self._capacity = capacity
        self._seen: OrderedDict[str, None] = OrderedDict()

    def seen(self, message_id: str) -> bool:
        """Record ``message_id`` and report whether it had been seen before."""
        if message_id in self._seen:
            self._seen.move_to_end(message_id)
            return True
        self._seen[message_id] = None
        if len(self._seen) > self._capacity:
            self._seen.popitem(last=False)
        return False


def _cause_of(payload: Mapping[str, Any]) -> str:
    """Причинные части полезной нагрузки: запрос и номер попытки.

    Именно они делают событие тем же самым событием. Идентификатор запроса один на исследование,
    номер попытки различает перезапуск после сбоя — вместе они отвечают на вопрос «это то же
    завершение или новое». Отсутствие любой из частей — не повод для случайности: пустая строка
    даст стабильный идентификатор по типу и саге, и это всё равно лучше, чем `uuid4()`.
    """
    return f"{payload.get('researchRequestId', '')}#{payload.get('attempt', '')}"


class KafkaTrendPublisher:
    """Publishes ``DomainAnalyzed`` / ``DomainAnalysisFailed`` / ``AnalysisProgressed``."""

    def __init__(
        self,
        producer: AIOKafkaProducer,
        *,
        topic: str = "horizon.analysis.events.v1",
        source: str = "analytics-service",
    ) -> None:
        """Store the producer and the target topic."""
        self._producer = producer
        self._topic = topic
        self._source = source

    async def publish_analyzed(self, payload: Mapping[str, Any], *, correlation_id: str) -> None:
        """Publish the analysis result — the single Python → Java hand-off (ADR-0016)."""
        await self._send("horizon.analysis.DomainAnalyzed", payload, correlation_id, terminal=True)

    async def publish_failed(self, payload: Mapping[str, Any], *, correlation_id: str) -> None:
        """Publish a failure instead of dying silently."""
        await self._send(
            "horizon.analysis.DomainAnalysisFailed", payload, correlation_id, terminal=True
        )

    async def publish_progress(self, payload: Mapping[str, Any], *, correlation_id: str) -> None:
        """Publish an intermediate progress update."""
        await self._send("horizon.analysis.AnalysisProgressed", payload, correlation_id)

    async def _send(
        self,
        message_type: str,
        payload: Mapping[str, Any],
        correlation_id: str,
        *,
        terminal: bool = False,
    ) -> None:
        """Wrap the payload in an envelope and send it, keyed by the saga id.

        Идентификатор сообщения у терминальных событий выводится из причины, а не случаен.
        `deterministic_message_id` заведена ровно для этого — чтобы переобработанная команда дала
        **тот же** `messageId` и проверка идемпотентности по нему (ADR-0003) сработала после
        перезапуска воркера. Вызывалась она до сих пор ниоткуда, а здесь стоял `uuid4()`: Kafka
        доставляет минимум однократно, и повторная обработка публиковала `DomainAnalyzed` с новым
        идентификатором — потребитель не опознавал повтор и заводил второй отчёт.

        У событий прогресса идентификатор остаётся случайным, и это не недоделка. Их на один
        анализ несколько, причинных частей, различающих стадии, в конверте нет, а общий ключ сделал
        бы их неразличимыми — то есть лечение было бы хуже болезни. Повторная доставка прогресса
        безвредна: он ничего не создаёт.
        """
        message = envelope(
            message_id=(
                deterministic_message_id(message_type, correlation_id, _cause_of(payload))
                if terminal
                else str(uuid4())
            ),
            message_type=message_type,
            occurred_at=datetime.now(tz=UTC),
            payload=payload,
            correlation_id=correlation_id,
            source=self._source,
        )
        await self._producer.send_and_wait(
            self._topic,
            value=_dumps(message),
            key=correlation_id.encode("utf-8"),
            headers=[
                ("type", message_type.encode("utf-8")),
                ("correlationId", correlation_id.encode("utf-8")),
            ],
        )


class AnalysisCommandConsumer:
    """Consumes ``AnalyzeDomain`` commands and dispatches them to a handler."""

    def __init__(
        self,
        *,
        bootstrap_servers: str,
        topic: str,
        group_id: str,
        handler: MessageHandler,
        concurrency: int = 2,
        liveness_file: str | None = None,
        max_poll_interval_ms: int = 45 * 60 * 1000,
    ) -> None:
        """Configure the consumer loop.

        ``max_poll_interval_ms`` — сколько обработка пачки вправе не возвращаться к опросу. Анализ
        идёт внутри цикла опроса, и с умолчанием aiokafka (пять минут) брокер исключал бы
        потребителя из группы посреди анализа — команда ушла бы на повторную доставку.
        """
        self._bootstrap_servers = bootstrap_servers
        self._topic = topic
        self._group_id = group_id
        self._handler = handler
        self._concurrency = max(1, concurrency)
        self._liveness_file = liveness_file
        self._max_poll_interval_ms = max_poll_interval_ms
        self._dedup = MessageDeduplicator()
        self._consumer: AIOKafkaConsumer | None = None
        self._stopping = asyncio.Event()

    async def run(self) -> None:
        """Run until :meth:`stop` is called, committing offsets after each message."""
        consumer = AIOKafkaConsumer(
            self._topic,
            bootstrap_servers=self._bootstrap_servers,
            group_id=self._group_id,
            enable_auto_commit=False,
            auto_offset_reset="earliest",
            max_poll_records=self._concurrency,
            max_poll_interval_ms=self._max_poll_interval_ms,
        )
        self._consumer = consumer
        await consumer.start()
        _LOGGER.info("worker.started", topic=self._topic, group=self._group_id)
        try:
            while not self._stopping.is_set():
                batch = await consumer.getmany(timeout_ms=1000, max_records=self._concurrency)
                self._touch_liveness()
                for records in batch.values():
                    for record in records:
                        try:
                            await self._handle(record.value)
                        except Exception:
                            # Without this the offset never advances: the process dies, restarts,
                            # re-reads the same record and dies again, blocking the partition for
                            # every other request. Failure handling belongs to `_handle`, which
                            # publishes DomainAnalysisFailed; this is the last resort for the case
                            # where even that failed.
                            _LOGGER.exception(
                                "worker.record_failed",
                                topic=self._topic,
                                group=self._group_id,
                            )
                if batch:
                    await consumer.commit()
        finally:
            await consumer.stop()
            self._consumer = None
            _LOGGER.info("worker.stopped")

    async def stop(self) -> None:
        """Ask the loop to finish after the current batch."""
        self._stopping.set()

    async def _handle(self, raw: bytes) -> None:
        """Decode one message, deduplicate it and hand it to the use case."""
        try:
            message = Envelope.from_dict(orjson.loads(raw))
        except (orjson.JSONDecodeError, TypeError, ValueError, KeyError) as error:
            # A malformed message can never succeed on retry: log it and move the offset on,
            # otherwise the partition is blocked forever by one bad record.
            _LOGGER.error("worker.malformed_message", error=str(error))
            return
        if self._dedup.seen(message.message_id):
            _LOGGER.info("worker.duplicate_skipped", message_id=message.message_id)
            return
        try:
            await self._handler(message)
        except Exception as error:
            _LOGGER.error("worker.handler_failed", error=str(error), exc_info=True)

    def _touch_liveness(self) -> None:
        """Refresh the liveness file the container healthcheck watches."""
        if not self._liveness_file:
            return
        try:
            from pathlib import Path

            Path(self._liveness_file).write_text("ok", encoding="utf-8")
        except OSError as error:  # pragma: no cover - a read-only /tmp is a deploy problem
            _LOGGER.warning("worker.liveness_write_failed", error=str(error))


async def build_producer(bootstrap_servers: str) -> AIOKafkaProducer:
    """Create and start an idempotent producer."""
    producer = AIOKafkaProducer(
        bootstrap_servers=bootstrap_servers,
        enable_idempotence=True,
        acks="all",
        linger_ms=20,
    )
    await producer.start()
    return producer
