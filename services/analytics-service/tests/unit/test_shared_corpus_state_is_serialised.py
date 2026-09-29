"""Два анализа в одном процессе не оказываются внутри корпусной секции одновременно.

Провайдер эмбеддингов и разбор корпуса живут в контейнере — один на процесс. Это и даёт экономию
([69], [71]), и это же делает их общим изменяемым состоянием. В воркере опасности нет: записи
разбираются строго по одной (`AnalysisCommandConsumer` идёт по пачке циклом, а `concurrency` задаёт
лишь её размер). А `POST /explain` уходит в `asyncio.to_thread`, и FastAPI обслуживает запросы
параллельно: два объяснения по разным снапшотам — два конвейера в двух потоках на одном провайдере.

Окно узкое и оттого хуже: `fit` присваивает `self._vectorizer` **неподогнанный** объект и только
потом подгоняет его. Соседний поток, попавший в это окно, получает либо исключение, либо векторы
чужого корпуса — и второе не выглядит ошибкой.

Проверка построена на встрече, а не на таймингах. Оба потока пытаются встретиться внутри `fit`:
встретились — значит корпусную секцию проходят одновременно и общее состояние ничем не защищено;
не встретились за отведённое время — значит проходят по очереди. Тайминговая версия этой проверки
была написана первой и оказалась пустой: потоки просто не пересеклись, и зелёный цвет ничего не
означал.

Разбор: ``docs/01-analysis/72-two-questions-one-vector-space.md``.
"""

from __future__ import annotations

import threading
from datetime import UTC, date, datetime

import numpy as np

from horizon_analytics.domain.extraction.corpus_cache import CorpusAnalysisCache
from horizon_analytics.domain.models import AnalysisParams, Document
from horizon_analytics.domain.pipeline import AnalysisPipeline, PipelineRequest
from horizon_analytics.domain.scoring.profile import MethodologyProfile
from horizon_analytics.domain.vectors import Matrix

#: Столько ждём встречи. Секунда — на два порядка больше, чем нужно потоку, чтобы дойти до `fit`.
MEETING_TIMEOUT = 1.0


def document(document_id: str, text: str, year: int) -> Document:
    return Document(
        document_id=document_id,
        source_id="openalex",
        source_class="JOURNAL_ARTICLE",
        external_id=document_id,
        title=text,
        abstract_text=text,
        published_on=date(year, 1, 1),
        url=f"https://example.org/{document_id}",
        fetched_at=datetime(2024, 1, 2, tzinfo=UTC),
    )


def corpus(word: str) -> tuple[Document, ...]:
    return tuple(
        document(f"{word}-{index}", f"{word} membrane electrode assembly study {index}", year)
        for index, year in enumerate((2020, 2021, 2022, 2023, 2024, 2025))
    )


class MeetingProvider:
    """Провайдер, пытающийся свести два потока внутри подгонки пространства."""

    model_id = "meeting-1"
    dimension = 8

    def __init__(self) -> None:
        """Барьер на двоих: встреча внутри `fit` и есть искомое событие."""
        self.barrier = threading.Barrier(2)
        self.met = False

    def fit(self, texts: list[str]) -> None:
        try:
            self.barrier.wait(timeout=MEETING_TIMEOUT)
        except threading.BrokenBarrierError:
            return
        self.met = True

    def embed(self, texts: list[str]) -> Matrix:
        return np.zeros((len(list(texts)), self.dimension), dtype=np.float64)


def run(pipeline: AnalysisPipeline, marker: str) -> None:
    pipeline.run(
        PipelineRequest(
            normalized_query=marker,
            query=marker,
            documents=corpus(marker),
            params=AnalysisParams(top_n=5, years_window=7),
            profile=MethodologyProfile.default(),
            window_from=date(2019, 1, 1),
            window_to=date(2025, 12, 31),
            today=date(2026, 8, 11),
            watch=frozenset(),
        )
    )


def test_two_corpora_never_share_the_space_at_once() -> None:
    """Встретились внутри `fit` — значит пространство одного корпуса видно анализу другого."""
    provider = MeetingProvider()
    cache = CorpusAnalysisCache()
    threads = [
        threading.Thread(
            target=run,
            args=(AnalysisPipeline(embedding_provider=provider, corpus_cache=cache), marker),
        )
        for marker in ("alpha", "beta")
    ]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()

    assert not provider.met, (
        "два конвейера оказались внутри подгонки пространства одновременно: "
        "общее состояние процесса ничем не защищено"
    )
