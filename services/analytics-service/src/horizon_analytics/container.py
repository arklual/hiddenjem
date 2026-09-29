"""Composition root — the one place that knows which adapter implements which port.

Keeping the wiring here rather than in the API or the worker is what lets both roles of the
single image (ADR-0014) share provably identical domain behaviour: they build the same
:class:`AnalyzeDomainUseCase` from the same settings.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from horizon_analytics.adapters.clock import build_clock
from horizon_analytics.adapters.corpus.fixture_loader import FixtureDocumentRepository
from horizon_analytics.adapters.embeddings.tfidf_svd import TfidfSvdEmbeddingProvider
from horizon_analytics.adapters.persistence.memory import (
    InMemoryDocumentRepository,
    InMemoryJobStore,
    InMemoryTrendPublisher,
)
from horizon_analytics.application.analyze_domain import AnalyzeDomainUseCase
from horizon_analytics.application.explain_term import ExplainTermUseCase
from horizon_analytics.application.localization import LocalizationService
from horizon_analytics.config import Settings
from horizon_analytics.domain.engines import SignalsConfiguration
from horizon_analytics.domain.extraction.corpus_cache import CorpusAnalysisCache
from horizon_analytics.domain.narration.base import TrendNarrator
from horizon_analytics.domain.narration.extractive import ExtractiveNarrator
from horizon_analytics.domain.ports import (
    AnalysisJobStore,
    Clock,
    DocumentRepository,
    EmbeddingProvider,
    MaturityProbe,
    TechnologyJudge,
    TrendPublisher,
)
from horizon_analytics.observability import get_logger
from horizon_analytics.openalex_keys import keys as openalex_keys

__all__ = [
    "Container",
    "build_container",
    "build_embedding_provider",
    "build_localization",
    "build_maturity_probe",
    "build_narrator",
    "build_signals",
    "build_technology_judge",
]

_LOGGER = get_logger(__name__)


def build_embedding_provider(settings: Settings) -> EmbeddingProvider:
    """Select the embedding provider, degrading to the default one when unavailable.

    ADR-0009 makes the provider pluggable; a misconfigured optional provider must not take
    the service down, so the failure is logged and the deterministic default takes over.
    """
    if settings.embedding_provider == "onnx":
        try:
            from horizon_analytics.adapters.embeddings.onnx import (
                OnnxEmbeddingProvider,
            )

            return OnnxEmbeddingProvider(
                settings.embedding_model_path, dimension=settings.embedding_dim
            )
        except Exception as error:
            _LOGGER.warning("embeddings.onnx_unavailable", error=str(error))
    elif settings.embedding_provider == "http":
        if settings.embedding_url:
            from horizon_analytics.adapters.embeddings.http import (
                HttpEmbeddingProvider,
            )

            return HttpEmbeddingProvider(settings.embedding_url, dimension=settings.embedding_dim)
        # Silence here would be the worst kind of degradation. The operator asked for a specific
        # embedding model; falling back to another one changes every score in every report, and an
        # unconfigured URL is a typo in a deployment manifest, not a decision anyone made.
        _LOGGER.warning(
            "embeddings.http_url_missing",
            hint="HORIZON_EMBEDDING_URL не задан — используется провайдер по умолчанию",
        )

    return TfidfSvdEmbeddingProvider(dimension=settings.embedding_dim, seed=settings.embedding_seed)


def build_narrator(settings: Settings) -> TrendNarrator:
    """Return the extractive narrator unless the LLM flag is explicitly on (ADR-0010)."""
    if settings.narration_mode == "llm" and settings.llm_enabled:
        _LOGGER.warning("narration.llm_requested_without_client")
    return ExtractiveNarrator()


def build_technology_judge(settings: Settings) -> TechnologyJudge | None:
    """Семантический судья кандидатов, если адрес сервиса моделей задан (ADR-0017).

    Молчаливого умолчания здесь быть не может в обе стороны: незаданный адрес означает «работать
    без модели», и это законный режим, а заданный адрес при выключенном флаге — противоречие в
    настройках, о котором стоит сказать вслух.
    """
    if not settings.nlp_url or not settings.nlp_judge_enabled:
        return None
    from horizon_analytics.adapters.nlp.http import HttpNlpClient, HttpTechnologyJudge

    client = HttpNlpClient(settings.nlp_url, timeout=settings.nlp_timeout_seconds)
    _LOGGER.info("judge.enabled", model=settings.nlp_judge_model, url=settings.nlp_url)
    return HttpTechnologyJudge(client, settings.nlp_judge_model)


def build_maturity_probe(settings: Settings) -> MaturityProbe | None:
    """Внешняя проверка зрелости по OpenAlex и Википедии, если она явно включена."""
    if not settings.external_maturity_enabled:
        return None
    from horizon_analytics.adapters.maturity.live import LiveMaturityProbe

    _LOGGER.info(
        "maturity.enabled",
        sources="openalex+wikipedia.en" if openalex_keys.current(settings.openalex_api_key) else "wikipedia.en",
        budget=settings.external_maturity_budget_seconds,
    )
    return LiveMaturityProbe(
        contact_email=settings.connector_contact_email,
        budget_seconds=settings.external_maturity_budget_seconds,
        openalex_api_key=settings.openalex_api_key,
    )


def build_signals(settings: Settings) -> SignalsConfiguration:
    """Настройки движка внешних признаков.

    Собираются всегда и ничего не стоят: сбор не начинается, пока не придёт анализ. Артефакт модели
    — из ``HORIZON_SIGNALS_MODEL``, а без него — поставляемый с пакетом: движок один, и свежая
    установка без переменной отказывала бы в каждом анализе. Проверять артефакт здесь нельзя —
    процесс API, которому анализ не нужен, обязан подниматься и с битым путём; отказ живёт в
    :func:`~horizon_analytics.domain.engines.build_engine`, то есть ровно там, где движок просят.
    """
    from horizon_analytics.adapters.signals.live import LiveSignalsProbe
    from horizon_analytics.resources import PACKAGED_SIGNALS_MODEL
    from horizon_analytics.signals import DEFAULT_CACHE_DIR

    cache = Path(settings.signals_cache_dir) if settings.signals_cache_dir else DEFAULT_CACHE_DIR
    proposer = None
    if settings.signals_propose_candidates and settings.nlp_url:
        from horizon_analytics.adapters.nlp.http import HttpNlpClient, HttpTechnologyProposer

        proposer = HttpTechnologyProposer(
            HttpNlpClient(settings.nlp_url, timeout=settings.nlp_timeout_seconds),
            settings.nlp_judge_model,
        )
        _LOGGER.info(
            "signals.proposer_enabled",
            url=settings.nlp_url,
            model=settings.nlp_judge_model,
            limit=settings.signals_proposed_limit,
        )
    elif settings.signals_propose_candidates:
        # Просили второй источник кандидатов и не дали адреса моделям. Молчать нельзя: движок
        # соберётся, посчитает по корпусу и будет выглядеть работающим ровно так же.
        _LOGGER.warning("signals.proposer_without_nlp_url")
    explicit = settings.signals_model_path.strip()
    model_path = Path(explicit) if explicit else PACKAGED_SIGNALS_MODEL
    # Какой артефакт считает отчёты — в журнал при старте: переменная, забытая на стенде, иначе
    # видна только по версии модели в отчёте, а её сверяет не каждый.
    _LOGGER.info(
        "signals.model_selected",
        path=str(model_path),
        origin="HORIZON_SIGNALS_MODEL" if explicit else "packaged",
    )
    return SignalsConfiguration(
        model_path=model_path,
        probe=LiveSignalsProbe(
            cache_dir=cache,
            cache_only=settings.signals_cache_only,
            budget_seconds=settings.signals_budget_seconds,
            term_budget_seconds=settings.signals_term_budget_seconds,
        ),
        top_candidates=settings.signals_top_candidates,
        proposer=proposer,
        proposed_limit=settings.signals_proposed_limit,
    )


def build_localization(settings: Settings) -> LocalizationService | None:
    """Русский слой выдачи, если адрес сервиса моделей задан (ТЗ: выдача на русском языке)."""
    if not settings.nlp_url or not settings.nlp_localization_enabled:
        return None
    from horizon_analytics.adapters.nlp.http import HttpNlpClient

    client = HttpNlpClient(settings.nlp_url, timeout=settings.nlp_timeout_seconds)
    _LOGGER.info("localization.enabled", url=settings.nlp_url)
    return LocalizationService(client=client)


@dataclass(frozen=True, slots=True)
class Container:
    """Resolved collaborators of one process."""

    settings: Settings
    clock: Clock
    documents: DocumentRepository
    embeddings: EmbeddingProvider
    #: Разбор корпуса, не зависящий от направления. Здесь, а не в конвейере: конвейер строится на
    #: каждый запрос, а разбор переживает их все — как и провайдер эмбеддингов рядом.
    corpus_cache: CorpusAnalysisCache
    publisher: TrendPublisher
    jobs: AnalysisJobStore
    narrator: TrendNarrator
    #: Оба сотрудника необязательны и отказывают независимо друг от друга (ADR-0017).
    technology_judge: TechnologyJudge | None = None
    localization: LocalizationService | None = None
    maturity_probe: MaturityProbe | None = None
    #: Настройки движка. Пустые по умолчанию — для тестов, собирающих контейнер вручную: анализ на
    #: таком контейнере получит отказ с названной причиной. Боевой путь собирает их
    #: :func:`build_signals`.
    signals: SignalsConfiguration = field(default_factory=SignalsConfiguration)

    def explain_use_case(self) -> ExplainTermUseCase:
        """Build the read-only trace replay.

        Deliberately wired without the publisher and the job store: an explanation must not be able
        to emit an event or claim a job for the analysis it is asking about.
        """
        return ExplainTermUseCase(
            documents=self.documents,
            embeddings=self.embeddings,
            corpus_cache=self.corpus_cache,
            clock=self.clock,
            signals=self.signals,
        )

    def use_case(self) -> AnalyzeDomainUseCase:
        """Build the analysis use case from the resolved ports."""
        return AnalyzeDomainUseCase(
            documents=self.documents,
            embeddings=self.embeddings,
            corpus_cache=self.corpus_cache,
            publisher=self.publisher,
            jobs=self.jobs,
            clock=self.clock,
            narrator=self.narrator,
            technology_judge=self.technology_judge,
            localization=self.localization,
            maturity_probe=self.maturity_probe,
            signals=self.signals,
        )


def build_container(
    settings: Settings,
    *,
    documents: DocumentRepository | None = None,
    publisher: TrendPublisher | None = None,
    jobs: AnalysisJobStore | None = None,
) -> Container:
    """Assemble a container, letting callers override any port (tests, worker, API).

    The document repository defaults to the fixture corpus when the file exists — this is
    what makes ``docker compose up`` produce a working, deterministic demo without a
    populated database.
    """
    repository = documents
    if repository is None and settings.ingestion_url:
        # Собранный корпус приходит от ingestion-service по внутреннему API. Это основной путь:
        # эталонный корпус ниже отвечает на любой идентификатор снапшота и потому способен
        # незаметно подменить собой настоящие данные — ровно это и происходило, пока адреса не
        # было.
        from horizon_analytics.adapters.corpus.http import HttpCorpusRepository

        repository = HttpCorpusRepository(
            settings.ingestion_url,
            page_size=settings.ingestion_page_size,
            internal_token=settings.internal_secret,
        )
        _LOGGER.info("corpus.over_http", url=settings.ingestion_url)
    if repository is None:
        path = Path(settings.fixture_corpus_path)
        if path.is_file():
            repository = FixtureDocumentRepository(path)
            # Предупреждение, а не сообщение: эталонный корпус отвечает на любой снапшот, и
            # развёртывание, случайно оказавшееся в этом режиме, выпускает правдоподобные отчёты
            # не о тех данных. Молчание здесь неотличимо от нормальной работы.
            _LOGGER.warning(
                "corpus.fixture_loaded",
                path=str(path),
                hint=(
                    "HORIZON_INGESTION_URL не задан — анализ пойдёт по эталонному корпусу, "
                    "а не по собранным документам"
                ),
            )
        else:
            repository = InMemoryDocumentRepository()
            _LOGGER.warning("corpus.fixture_missing", path=str(path))

    return Container(
        settings=settings,
        clock=build_clock(settings.fixed_clock or None),
        documents=repository,
        embeddings=build_embedding_provider(settings),
        corpus_cache=CorpusAnalysisCache(),
        publisher=publisher if publisher is not None else InMemoryTrendPublisher(),
        jobs=jobs if jobs is not None else InMemoryJobStore(),
        narrator=build_narrator(settings),
        technology_judge=build_technology_judge(settings),
        localization=build_localization(settings),
        maturity_probe=build_maturity_probe(settings),
        signals=build_signals(settings),
    )
