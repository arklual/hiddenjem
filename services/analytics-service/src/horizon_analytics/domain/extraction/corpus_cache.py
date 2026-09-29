"""Разбор корпуса, не зависящий от направления, — считается один раз на снапшот.

Зачем
─────
Конвейер выполняет два шага, у которых на входе только корпус и параметры извлечения: построение
нормализатора (`TermNormalizer.from_texts`) и извлечение кандидатов (`extract_candidates`).
Формулировка направления в них не участвует ни в каком виде. При этом аналитик спрашивает по
одному снапшоту несколько направлений — этим продукт и занимается, — и до сих пор каждый вопрос
считал их заново.

Замер на настоящем корпусе, усечённом до `max_documents`: повторный запрос по тому же снапшоту —
21.7 с, из которых нормализатор и извлечение — около 12 с. Разбор:
``docs/01-analysis/71-the-corpus-parsed-once-per-question.md``.

Почему отдельный объект, а не память конвейера
──────────────────────────────────────────────
`AnalysisPipeline` строится на каждый запрос (`analyze_domain.py`), поэтому его собственное поле
не пережило бы и одного вопроса. Живёт всё время процесса контейнер (`build_container`) — там же,
где провайдер эмбеддингов, у которого ровно та же природа: состояние, зависящее от корпуса.
Отсюда и сюда же — явный сотрудник, передаваемый конструктором, а не глобальная переменная модуля:
глобальную нельзя ни подменить в тесте, ни увидеть в проводке.

Что в ключе
───────────
Отпечаток текстов корпуса в порядке подачи плюс параметры, влияющие на разбор. Не
`corpus_snapshot_id`: идентификатор приходит извне и может солгать, а подменённые кандидаты
неверными не выглядят. Тексты солгать не могут.
"""

from __future__ import annotations

import hashlib
import threading
from collections.abc import Iterator, Sequence
from contextlib import contextmanager
from dataclasses import dataclass

from horizon_analytics.domain.extraction.candidates import CandidateExtraction, extract_candidates
from horizon_analytics.domain.extraction.normalization import TermNormalizer
from horizon_analytics.domain.models import Document

__all__ = ["CorpusAnalysis", "CorpusAnalysisCache"]


@dataclass(frozen=True, slots=True)
class CorpusAnalysis:
    """Разбор корпуса: нормализатор и кандидаты до отсева по частоте и фильтрам."""

    normalizer: TermNormalizer
    extraction: CandidateExtraction


class CorpusAnalysisCache:
    """Помнит разбор последнего корпуса. Ровно одного: снапшоты сменяют друг друга, а не чередуются.

    Место на один разбор, потому что второй ничего не даёт: аналитик спрашивает несколько
    направлений по свежему снапшоту, а не возвращается к позапрошлому. Хранить больше — значит
    держать в памяти корпус, к которому уже не вернутся, а память здесь и так предмет
    отдельного ограничения (`ANALYTICS_WORKER_MEM_LIMIT` против `max_documents`).
    """

    def __init__(self) -> None:
        """Пустая память: первый же корпус будет разобран."""
        self._key: str | None = None
        self._value: CorpusAnalysis | None = None
        self._lock = threading.RLock()

    @contextmanager
    def exclusive(self) -> Iterator[None]:
        """Владение корпусной секцией процесса на время одного анализа.

        Замок стоит здесь, потому что здесь же живёт то, что он защищает: разбор корпуса и — через
        конвейер — провайдер эмбеддингов, второй сотрудник уровня процесса с состоянием, зависящим
        от корпуса. Один замок на обоих, потому что инвариант один: пространство, которым считают,
        подогнано под тот корпус, который сейчас разбирают.

        Зачем вообще. В воркере анализы идут по одному: `AnalysisCommandConsumer` разбирает пачку
        циклом, а `concurrency` задаёт лишь её размер. Но `POST /explain` уходит в
        `asyncio.to_thread`, а FastAPI обслуживает запросы параллельно — два объяснения по разным
        снапшотам дают два конвейера в двух потоках на одном провайдере. Окно узкое и оттого хуже:
        `fit` присваивает неподогнанный векторизатор и только потом подгоняет его, так что сосед
        получает либо исключение, либо векторы чужого корпуса — и второе не выглядит ошибкой.

        Чем платим. Два одновременных объяснения по **разным** снапшотам идут по очереди. По
        одному снапшоту — почти бесплатно: второй входит в уже подогнанное пространство и в уже
        разобранный корпус (12.1 с против 104 с). Обратная сторона — честная и записана в
        ``docs/01-analysis/72-two-questions-one-vector-space.md``: место, где конвейеры могли бы идти параллельно, теперь их разделяет.

        Замок повторный (`RLock`): конвейер берёт его на весь прогон, а объяснение термина
        прогоняет конвейер внутри уже начатого разбора.
        """
        with self._lock:
            yield

    def analyse(
        self,
        documents: Sequence[Document],
        *,
        stopwords: frozenset[str],
        ngram_min: int,
        ngram_max: int,
    ) -> CorpusAnalysis:
        """Разбор корпуса — из памяти, если корпус и параметры те же."""
        key = self._digest(documents, ngram_min=ngram_min, ngram_max=ngram_max)
        if key == self._key and self._value is not None:
            return self._value

        texts = [document.text for document in documents]
        normalizer = TermNormalizer.from_texts(texts)
        analysis = CorpusAnalysis(
            normalizer=normalizer,
            extraction=extract_candidates(
                documents,
                normalizer=normalizer,
                stopwords=stopwords,
                ngram_min=ngram_min,
                ngram_max=ngram_max,
            ),
        )
        self._key = key
        self._value = analysis
        return analysis

    @staticmethod
    def _digest(documents: Sequence[Document], *, ngram_min: int, ngram_max: int) -> str:
        """Отпечаток корпуса и параметров разбора. Порядок значим: от него зависит словарь."""
        digest = hashlib.sha256(f"{ngram_min}:{ngram_max}\x00".encode())
        for document in documents:
            digest.update(document.text.encode("utf-8"))
            digest.update(b"\x00")
        return digest.hexdigest()
