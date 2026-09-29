"""Переобработанная команда даёт то же терминальное событие, а не второе.

Kafka доставляет минимум однократно: после перезапуска воркер может обработать ту же команду
заново. Проверка идемпотентности потребителя идёт по `messageId` (ADR-0003), и работает она только
если идентификатор выводится из причины, а не случаен. `deterministic_message_id` заведена ровно
для этого и до сих пор не вызывалась ниоткуда — издатель ставил `uuid4()`.

Разбор: пункт 13 бэклога.
"""

from __future__ import annotations

import asyncio
from typing import Any

from horizon_analytics.adapters.messaging.kafka import KafkaTrendPublisher


class Recorder:
    """Продюсер, который ничего не отправляет и всё запоминает."""

    def __init__(self) -> None:
        """Пустая история отправок."""
        self.sent: list[dict[str, Any]] = []

    async def send_and_wait(self, topic: str, value: bytes, **kwargs: Any) -> None:
        import json

        self.sent.append(json.loads(value))


PAYLOAD = {"researchRequestId": "req-1", "attempt": 1, "trends": []}


def publish(publisher: KafkaTrendPublisher, kind: str, payload: dict[str, Any]) -> dict[str, Any]:
    recorder = publisher._producer
    before = len(recorder.sent)
    asyncio.run(getattr(publisher, kind)(payload, correlation_id="saga-1"))
    return recorder.sent[before]


def test_the_same_analysis_published_twice_carries_one_identity() -> None:
    """Ровно то, ради чего функция была написана: повтор опознаётся потребителем."""
    publisher = KafkaTrendPublisher(Recorder())  # type: ignore[arg-type]
    first = publish(publisher, "publish_analyzed", dict(PAYLOAD))
    second = publish(publisher, "publish_analyzed", dict(PAYLOAD))
    assert first["messageId"] == second["messageId"]


def test_a_second_attempt_is_a_different_event() -> None:
    """Перезапуск после сбоя — новое завершение, и различает их номер попытки."""
    publisher = KafkaTrendPublisher(Recorder())  # type: ignore[arg-type]
    first = publish(publisher, "publish_analyzed", dict(PAYLOAD))
    second = publish(publisher, "publish_analyzed", {**PAYLOAD, "attempt": 2})
    assert first["messageId"] != second["messageId"]


def test_success_and_failure_are_different_events() -> None:
    """Тип входит в причину: иначе отказ и успех одной попытки слились бы в одно сообщение."""
    publisher = KafkaTrendPublisher(Recorder())  # type: ignore[arg-type]
    ok = publish(publisher, "publish_analyzed", dict(PAYLOAD))
    failed = publish(publisher, "publish_failed", dict(PAYLOAD))
    assert ok["messageId"] != failed["messageId"]


def test_progress_keeps_a_fresh_identity_for_every_stage() -> None:
    """Прогресса на анализ несколько, и общий ключ сделал бы стадии неразличимыми."""
    publisher = KafkaTrendPublisher(Recorder())  # type: ignore[arg-type]
    first = publish(publisher, "publish_progress", {**PAYLOAD, "stage": "EXTRACTING"})
    second = publish(publisher, "publish_progress", {**PAYLOAD, "stage": "SCORING"})
    assert first["messageId"] != second["messageId"]
