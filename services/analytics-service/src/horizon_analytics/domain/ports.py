"""Ports — the only way the domain and the use cases reach the outside world.

Every protocol here is implemented at least twice: once for real infrastructure under
``adapters/`` and once in memory under ``adapters/persistence/memory.py``. The in-memory
set is not test scaffolding only — it also powers ``HORIZON_MENTION_STORE=memory`` for
constrained local runs.
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from datetime import date, datetime
from typing import Literal, Protocol, runtime_checkable

from horizon_analytics.domain.models import Document
from horizon_analytics.domain.vectors import Matrix

__all__ = [
    "AnalysisJobRecord",
    "AnalysisJobStore",
    "Clock",
    "DocumentRepository",
    "EmbeddingProvider",
    "MaturityProbe",
    "MentionFact",
    "MentionFactStore",
    "ProgressReporter",
    "ProgressStage",
    "SignalsProbe",
    "SnapshotNotFoundError",
    "TechnologyJudge",
    "TechnologyProposer",
    "TrendPublisher",
]

ProgressStage = Literal["EXTRACTING", "EMBEDDING", "CLUSTERING", "SCORING", "NARRATING"]


class SnapshotNotFoundError(LookupError):
    """Raised when the requested corpus snapshot does not exist."""


@runtime_checkable
class Clock(Protocol):
    """Injected time source. ``datetime.now()`` is forbidden in domain code (ADR-0015)."""

    def now(self) -> datetime:
        """Current instant, timezone aware (UTC)."""
        ...

    def today(self) -> date:
        """Current date in UTC."""
        ...


@runtime_checkable
class DocumentRepository(Protocol):
    """Read access to the corpus snapshot being analysed."""

    async def load_snapshot(self, snapshot_id: str) -> Sequence[Document]:
        """Load every document of a snapshot.

        Implementations must apply an explicit ``ORDER BY document_id`` — implicit
        ordering is the single most common source of non-reproducible analytics.

        Raises:
            SnapshotNotFoundError: when the snapshot id is unknown.
        """
        ...

    async def snapshot_window(self, snapshot_id: str) -> tuple[date, date]:
        """Return the ``(window_from, window_to)`` of a snapshot."""
        ...


@runtime_checkable
class EmbeddingProvider(Protocol):
    """Pluggable vectoriser (ADR-0009). Must be deterministic for a fixed model id."""

    @property
    def model_id(self) -> str:
        """Identifier stored alongside every embedding and echoed in the result."""
        ...

    @property
    def dimension(self) -> int:
        """Dimensionality of the produced vectors."""
        ...

    def fit(self, corpus: Sequence[str]) -> None:
        """Fit the provider on the analysed corpus. A no-op for stateless providers."""
        ...

    def embed(self, texts: Sequence[str]) -> Matrix:
        """Return an ``(len(texts), dimension)`` matrix of embeddings."""
        ...


@runtime_checkable
class TechnologyJudge(Protocol):
    """Семантическая проверка «является ли строка названием технологии» (ADR-0017).

    Существует потому, что различие, о которое спотыкается отбор кандидатов, не статистическое.
    Замер разбора 30: балл эмерджентности мусорных именных групп (44.9–46.4) и настоящих тем
    (45.7–55.7) перекрываются полностью, и термхуд их тоже не делит — «state space» стоит в
    98-м перцентиле. Отличаются они только смыслом, и поэтому единственный работающий судья —
    тот, кто читает смысл.

    Контракт узкий по трём причинам, и все три — требования ТЗ либо методологии.

    * **Только отсев.** Судья получает уже найденные поиском строки и может лишь убрать часть.
      Добавить он не может ничего, поэтому выдача не формируется «исключительно на основании
      знаний языковой модели».
    * **Не влияет на балл.** Проверка идёт после того, как ранжирование посчитано, и меняет
      только состав, а не порядок и не числа.
    * **Отказ равен отсутствию.** Реализация обязана вернуть ``None`` там, где ответа нет, а
      конвейер обязан трактовать ``None`` как «оставить»: выключенный судья даёт ровно ту
      выдачу, которая была до его появления.
    """

    @property
    def model_id(self) -> str:
        """Имя модели, которое уходит в отчёт и в лог (раскрытие по ТЗ §3.1)."""
        ...

    def judge(self, candidates: Sequence[str]) -> Mapping[str, object]:
        """Вернуть ``{строка: вердикт}``; отсутствие ключа читается как «не ответил»."""
        ...


class MaturityProbe(Protocol):
    """Проверка по открытым источникам за пределами корпуса: не массовая ли уже технология.

    Существует потому, что корпус видит только себя. Правило мейнстрима движка меряет долю темы
    в литературе направления, собранной под этот запрос, — полторы-три тысячи документов. О том,
    что по «federated learning» за три года вышли десятки тысяч работ, а в Википедии статья с 2019
    года, корпус не знает и знать не может. Этап 1 (замер на размеченном датасете) судит именно
    по этим внешним следам, и продукт, не спрашивающий их, судил бы на открытом запросе иначе, чем
    на проверке точности.

    Контракт — тот же, что у семантического судьи, и по тем же причинам:

    * **только отсев**: проверка получает найденные поиском названия и может лишь убрать часть;
    * **не влияет на балл**: идёт после ранжирования и меняет состав, а не числа;
    * **молчание — оставить**: источник не ответил, бюджет времени кончился, фраза неоднозначна —
      тема остаётся.
    """

    @property
    def source_id(self) -> str:
        """Какие источники спрошены — уходит в отчёт рядом с причиной исключения."""
        ...

    def probe(self, titles: Sequence[str]) -> Mapping[str, object]:
        """Вернуть ``{название: вердикт}``; отсутствие ключа читается как «не выяснено»."""
        ...


class SignalsProbe(Protocol):
    """Внешние признаки технологии из открытых источников — данные второго движка.

    Существует потому, что движок ``signals`` обязан оставаться чистой функцией от того, что ему
    дали: сбор ходит в сеть, а конвейер — нет. Порт разрывает это ровно в одном месте, и потому
    прогон движка на предзаполненном кэше воспроизводим до последнего знака.

    Контракт узкий и весь про отказ, потому что отказ здесь — нормальное состояние, а не авария.

    * **Отказ равен отсутствию.** Источник не ответил, фраза не нашлась, бюджет кончился — ключа
      в ответе просто нет. Реализация не вправе подставить ноль: ноль работ и «OpenAlex молчит» —
      два разных наблюдения, и модель, обученная не различать их, научится считать недоступность
      источника признаком незрелости.
    * **Ничего не бросает.** Сетевой сбой не должен ронять анализ: пустой ответ означает, что все
      темы посчитаны по корпусной части, и отчёт выйдет с пометкой об этом.
    * **Имена признаков — из домена.** Ключи вложенных словарей берутся из
      ``horizon_analytics.domain.signal_scoring.FEATURES``; всё, чего нет в этом списке,
      игнорируется при скоринге.
    """

    @property
    def source_id(self) -> str:
        """Какие источники спрошены — уходит в журнал рядом с числом собранных тем."""
        ...

    def features(self, terms: Sequence[str]) -> Mapping[str, Mapping[str, float]]:
        """Вернуть ``{фраза: {признак: значение}}`` для тех фраз, о которых что-то известно."""
        ...

    def works(self, terms: Sequence[str]) -> Mapping[str, Sequence[Document]]:
        """Вернуть по несколько настоящих работ на фразу — доказательную базу темы.

        Спрашивается отдельно от признаков и только о тех фразах, которые уже прошли проверку
        измеренными свидетельствами: счётчики отвечают «сколько», а отчёту нужно «что именно».
        Тема, о которой не нашлось ни одной работы, не публикуется — инвариант BR-A6 («тренд без
        доказательств не публикуется») для предложенных имён и есть та граница, за которой выдача
        перестала бы опираться на источники.
        """
        ...


class TechnologyProposer(Protocol):
    """Имена технологий направления, предложенные генеративной моделью (ТЗ §3.1).

    Существует потому, что извлечение достаёт кандидатов только из собранного корпуса, и у этого
    пути измерен потолок: из ста технологий размеченного эталона у 82 фраза не встречается в
    корпусе ни разу, а шестнадцать из них OpenAlex знает сотнями работ
    (`docs/01-analysis/94-checking-my-own-explanations.md`, Э1). Их не «плохо отранжировали» — их
    не спросили, и никакой скоринг корпусных кандидатов этого не исправит.

    Граница, за которой предложение стало бы выдачей, проведена **не здесь**, а в движке: имя
    публикуется только после того, как внешние источники показали по нему измеренную активность и
    дали хотя бы одну настоящую работу. Модель говорит, о чём спросить; отвечают источники —
    поэтому выдача не формируется «исключительно на основании знаний языковой модели».

    Отказ равен пустому списку: движок считает по корпусу ровно так, как считал без модели.
    """

    @property
    def model_id(self) -> str:
        """Имя модели — раскрытие по ТЗ §3.1; уходит в журнал рядом с числом предложенных имён."""
        ...

    def propose(self, direction: str, limit: int) -> Sequence[str]:
        """Вернуть до ``limit`` английских имён технологий этого направления."""
        ...


class MentionFact:
    """One ``term × document`` mention fact, as stored in ClickHouse.

    A plain class rather than a dataclass so that the port module stays importable with no
    dependency at all; the concrete field set mirrors ``horizon.term_mentions``.
    """

    __slots__ = (
        "citation_count",
        "country",
        "document_id",
        "occurrences",
        "organization",
        "organization_type",
        "period_start",
        "published_on",
        "snapshot_id",
        "source_class",
        "source_id",
        "term_normalized",
        "venue",
    )

    def __init__(
        self,
        *,
        term_normalized: str,
        document_id: str,
        period_start: date,
        published_on: date,
        source_id: str,
        source_class: str,
        organization: str,
        organization_type: str,
        country: str,
        venue: str,
        citation_count: int,
        occurrences: int,
        snapshot_id: str,
    ) -> None:
        """Store the fact fields verbatim."""
        self.term_normalized = term_normalized
        self.document_id = document_id
        self.period_start = period_start
        self.published_on = published_on
        self.source_id = source_id
        self.source_class = source_class
        self.organization = organization
        self.organization_type = organization_type
        self.country = country
        self.venue = venue
        self.citation_count = citation_count
        self.occurrences = occurrences
        self.snapshot_id = snapshot_id

    def as_row(self) -> Mapping[str, object]:
        """Row representation used by the ClickHouse and Postgres adapters."""
        return {name: getattr(self, name) for name in sorted(self.__slots__)}


@runtime_checkable
class MentionFactStore(Protocol):
    """Write/read side of the mention fact table (ClickHouse, or Postgres as fallback)."""

    async def record_mentions(self, facts: Sequence[MentionFact]) -> int:
        """Persist mention facts; returns the number of rows written."""
        ...

    async def period_statistics(
        self, snapshot_id: str, term_normalized: str
    ) -> Mapping[str, tuple[int, int]]:
        """Return ``{period_label: (df, tf)}`` for one term of one snapshot."""
        ...


@runtime_checkable
class TrendPublisher(Protocol):
    """Outbound events of the analytics service (ADR-0016 contract boundary)."""

    async def publish_analyzed(self, payload: Mapping[str, object], *, correlation_id: str) -> None:
        """Publish ``DomainAnalyzed`` — the single Python → Java hand-off."""
        ...

    async def publish_failed(self, payload: Mapping[str, object], *, correlation_id: str) -> None:
        """Publish ``DomainAnalysisFailed`` (``failure.event.json``)."""
        ...

    async def publish_progress(self, payload: Mapping[str, object], *, correlation_id: str) -> None:
        """Publish ``AnalysisProgressed``."""
        ...


@runtime_checkable
class ProgressReporter(Protocol):
    """Stage-by-stage progress of a long analysis run."""

    async def report(self, stage: ProgressStage, percent: int, message: str | None = None) -> None:
        """Report that ``stage`` reached ``percent`` completion."""
        ...


class AnalysisJobRecord:
    """State of one ``analytics.analysis_jobs`` row."""

    __slots__ = ("attempt", "research_request_id", "result", "status")

    def __init__(
        self,
        *,
        research_request_id: str,
        attempt: int,
        status: str,
        result: Mapping[str, object] | None = None,
    ) -> None:
        """Store the job fields verbatim."""
        self.research_request_id = research_request_id
        self.attempt = attempt
        self.status = status
        self.result = result


@runtime_checkable
class AnalysisJobStore(Protocol):
    """Idempotency ledger keyed by ``(researchRequestId, attempt)`` (data model §4)."""

    async def find(self, research_request_id: str, attempt: int) -> AnalysisJobRecord | None:
        """Return an existing job, if this ``(request, attempt)`` was already handled."""
        ...

    async def start(
        self,
        research_request_id: str,
        attempt: int,
        *,
        snapshot_id: str,
        methodology_version: str,
        profile: Mapping[str, object],
        started_at: datetime,
    ) -> bool:
        """Claim the job. Returns ``False`` when another worker already claimed it."""
        ...

    async def succeed(
        self,
        research_request_id: str,
        attempt: int,
        *,
        result: Mapping[str, object],
        stage_timings: Mapping[str, float],
        finished_at: datetime,
    ) -> None:
        """Mark the job as ``SUCCEEDED`` and store the published payload."""
        ...

    async def fail(
        self,
        research_request_id: str,
        attempt: int,
        *,
        code: str,
        message: str,
        finished_at: datetime,
    ) -> None:
        """Mark the job as ``FAILED`` with an error code and message."""
        ...
