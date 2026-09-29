"""Корпус снапшота по HTTP от ingestion-service (ADR-0006, ADR-0016).

Существует потому, что до сих пор собранный корпус до движка **не доходил вовсе**. Питоновский
репозиторий читал ``analytics.snapshot_documents`` — таблицу, которую никто никогда не заполнял;
на стороне Java был эндпоинт, отдающий идентификаторы документов, и не было эндпоинта, отдающего
сами документы. Движок в таком случае молча брал эталонный корпус, отвечающий на любой
идентификатор снапшота, и анализировал фикстуру: сбор отрабатывал, отчёт собирался, и ни одна
строка в нём не относилась к тому, что нашли источники.

Правило архитектуры звучит «сервис не читает чужую базу», а не «сервис не зовёт соседа». Запрос
неизменного, уже известного набора документов не является ни долгим, ни требующим переживания
отказа, поэтому здесь синхронный HTTP, а не событие.

Формат ответа — канонический ``document-ingested``: тот же, что у событий и у строк эталонного
корпуса. Разбор поэтому общий с загрузчиком фикстуры; второй разбор разошёлся бы с первым.
"""

from __future__ import annotations

from collections.abc import Sequence
from datetime import date

import httpx

from horizon_analytics.adapters.corpus.fixture_loader import parse_document
from horizon_analytics.domain.models import Document
from horizon_analytics.domain.ports import SnapshotNotFoundError
from horizon_analytics.observability import get_logger

__all__ = ["HttpCorpusRepository"]

_LOGGER = get_logger(__name__)


class HttpCorpusRepository:
    """Читает снапшот постранично из внутреннего API ingestion-service."""

    def __init__(
        self,
        base_url: str,
        *,
        page_size: int = 500,
        timeout: float = 120.0,
        internal_token: str = "",
    ) -> None:
        """Настроить клиент; соединение открывается на каждый снапшот и закрывается после."""
        self._base_url = base_url.rstrip("/")
        self._page_size = max(1, min(page_size, 2000))
        self._timeout = timeout
        # Внутренняя поверхность сбора закрыта общим секретом — тем же, которым закрыт
        # `/internal/analyze` у самого движка. Движок не пользователь: у него нет ни учётной
        # записи, ни refresh-цепочки, и выдавать ему JWT значило бы завести служебного
        # пользователя с правом читать весь корпус.
        self._headers = {"X-Internal-Token": internal_token} if internal_token else {}

    async def load_snapshot(self, snapshot_id: str) -> Sequence[Document]:
        """Загрузить все документы снапшота в порядке, заданном его содержимым.

        Порядок несущий: он определяет хэш снапшота, а значит и воспроизводимость анализа.
        Поэтому страницы склеиваются как пришли, без сортировки на стороне движка.
        """
        documents: list[Document] = []
        async with httpx.AsyncClient(
            base_url=self._base_url, timeout=self._timeout, headers=self._headers
        ) as client:
            offset = 0
            while True:
                response = await client.get(
                    f"/internal/v1/snapshots/{snapshot_id}/documents/full",
                    params={"offset": offset, "limit": self._page_size},
                )
                if response.status_code == 404:
                    raise SnapshotNotFoundError(snapshot_id)
                response.raise_for_status()
                page = response.json().get("documents") or []
                if not page:
                    break
                for payload in page:
                    try:
                        documents.append(parse_document(payload))
                    except Exception as error:
                        _LOGGER.warning(
                            "corpus.document_unparsable",
                            snapshot=snapshot_id,
                            error=str(error),
                        )
                offset += len(page)
                if len(page) < self._page_size:
                    break
        if not documents:
            # Пустой снапшот и отсутствующий снапшот — разные вещи, и вторая означает поломку.
            # Молча вернуть пустой корпус значило бы выпустить отчёт «сигналов не найдено» там,
            # где не найдено соединения.
            raise SnapshotNotFoundError(snapshot_id)
        _LOGGER.info("corpus.loaded_over_http", snapshot=snapshot_id, documents=len(documents))
        return tuple(documents)

    async def snapshot_window(self, snapshot_id: str) -> tuple[date, date]:
        """Окно снапшота, как его записал сбор."""
        async with httpx.AsyncClient(
            base_url=self._base_url, timeout=self._timeout, headers=self._headers
        ) as client:
            response = await client.get(f"/internal/v1/snapshots/{snapshot_id}")
            if response.status_code == 404:
                raise SnapshotNotFoundError(snapshot_id)
            response.raise_for_status()
            payload = response.json()
        return (
            date.fromisoformat(str(payload["windowFrom"])),
            date.fromisoformat(str(payload["windowTo"])),
        )
