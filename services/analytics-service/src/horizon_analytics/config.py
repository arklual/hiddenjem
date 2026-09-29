"""12-factor configuration (pydantic-settings).

Every knob is an environment variable with the ``HORIZON_``/service prefix already used by
``.env.example`` at the repository root, so a container started from the compose file needs
no extra wiring.
"""

from __future__ import annotations

from datetime import datetime
from functools import lru_cache
from typing import Literal

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

__all__ = ["Settings", "get_settings"]

Role = Literal["api", "worker"]
EmbeddingProviderName = Literal["tfidf-svd", "onnx", "http"]
NarrationMode = Literal["extractive", "llm"]
MentionStore = Literal["clickhouse", "postgres", "memory"]


class Settings(BaseSettings):
    """Runtime configuration of the analytics service."""

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
        case_sensitive=False,
    )

    # ── role & HTTP ──
    role: Role = Field(default="api", alias="HORIZON_ANALYTICS_ROLE")
    host: str = Field(default="0.0.0.0", alias="HORIZON_ANALYTICS_HOST")
    port: int = Field(default=8000, alias="HORIZON_ANALYTICS_PORT")
    internal_secret: str = Field(default="", alias="HORIZON_ANALYTICS_INTERNAL_SECRET")
    """Shared secret guarding ``POST /internal/analyze``. Empty disables the endpoint."""

    # ── logging ──
    log_level: str = Field(default="INFO", alias="HORIZON_LOG_LEVEL")
    log_format: str = Field(default="json", alias="HORIZON_LOG_FORMAT")

    # ── methodology ──
    methodology_version: str = Field(default="1.0.0", alias="HORIZON_METHODOLOGY_VERSION")
    # Движок не настраивается: он один — ``signals``. Переменная ``HORIZON_ENGINE`` прежних
    # развёртываний не читается и ни на что не влияет (``extra="ignore"``).
    embedding_provider: EmbeddingProviderName = Field(
        default="tfidf-svd", alias="HORIZON_EMBEDDING_PROVIDER"
    )
    embedding_dim: int = Field(default=384, alias="HORIZON_EMBEDDING_DIM")
    embedding_seed: int = Field(default=20260805, alias="HORIZON_EMBEDDING_SEED")
    embedding_url: str = Field(default="", alias="HORIZON_EMBEDDING_URL")
    embedding_model_path: str = Field(default="", alias="HORIZON_EMBEDDING_MODEL_PATH")
    narration_mode: NarrationMode = Field(default="extractive", alias="HORIZON_NARRATIVE_MODE")
    llm_enabled: bool = Field(default=False, alias="HORIZON_LLM_ENABLED")

    # ── сервис вывода моделей (ADR-0017) ──
    #
    # Пустой адрес выключает и семантический фильтр, и русский слой: движок ведёт себя ровно так,
    # как вёл до их появления. Это не деградация «на всякий случай», а свойство контракта —
    # воспроизводимость гарантируется именно в этом режиме.
    #: Внутренний адрес ingestion-service: откуда движок берёт собранный корпус (ADR-0006).
    #:
    #: Пустой адрес означает «работать по эталонному корпусу» — законный режим демонстрации без
    #: базы. Но это именно режим, а не умолчание на случай ошибки: заданный адрес, который не
    #: отвечает, обязан привести к отказу анализа, а не к молчаливой подмене корпуса фикстурой.
    ingestion_url: str = Field(default="", alias="HORIZON_INGESTION_URL")
    ingestion_page_size: int = Field(default=500, alias="HORIZON_INGESTION_CORPUS_PAGE_SIZE")

    nlp_url: str = Field(default="", alias="HORIZON_NLP_URL")
    nlp_timeout_seconds: float = Field(default=240.0, alias="HORIZON_NLP_TIMEOUT_SECONDS")
    #: Имя модели, которой размечены кандидаты. Раскрывается в отчёте и в логе (ТЗ §3.1) и
    #: задаётся отдельно от адреса: сменив модель на стенде, её обязаны переименовать и здесь,
    #: иначе отчёт продолжит называть прежнюю.
    nlp_judge_model: str = Field(default="horizon-qwen3.5-4b", alias="HORIZON_NLP_JUDGE_MODEL")
    #: Отдельные выключатели для двух независимых применений: отсев кандидатов и русский слой.
    nlp_judge_enabled: bool = Field(default=True, alias="HORIZON_NLP_JUDGE_ENABLED")
    nlp_localization_enabled: bool = Field(default=True, alias="HORIZON_NLP_LOCALIZATION_ENABLED")

    # ── движок внешних признаков (engine=signals) ──
    #: Путь к артефакту обученной модели: список признаков, веса, сдвиг, нормировка, версия, дата
    #: обучения и метрика на отложенной части.
    #:
    #: Пусто — артефакт, поставляемый с пакетом (``horizon_analytics/resources/signals-model.json``,
    #: копия ``docs/03-methodology/signals-model.json``): это обученная модель, а не выдуманные
    #: веса, и версия её названа в каждом отчёте. Заданный путь важнее; путь к файлу, которого нет,
    #: — отказ анализа с названной причиной, а не откат к поставляемой модели.
    signals_model_path: str = Field(default="", alias="HORIZON_SIGNALS_MODEL")
    #: Куда складывать собранные внешние признаки. Общий каталог на развёртывание: второй анализ
    #: того же направления не платит за сбор ничего.
    signals_cache_dir: str = Field(default="", alias="HORIZON_SIGNALS_CACHE_DIR")
    #: Скольких кандидатов спрашивать у внешних источников. Остальные считаются по корпусной
    #: части с пометкой об этом — они не выбывают.
    signals_top_candidates: int = Field(default=150, alias="HORIZON_SIGNALS_TOP_CANDIDATES")
    #: Просить ли у генеративной модели имена технологий сверх корпусных кандидатов.
    #:
    #: Выключено по умолчанию, и это не осторожность: умолчание стенда не должно меняться само
    #: вместе с появлением новой возможности. Включённый второй источник требует HORIZON_NLP_URL;
    #: без адреса имена просить не у кого, и движок считает по корпусу.
    signals_propose_candidates: bool = Field(
        default=False, alias="HORIZON_SIGNALS_PROPOSE_CANDIDATES"
    )
    #: Сколько имён просить на одно направление.
    signals_proposed_limit: int = Field(default=60, alias="HORIZON_SIGNALS_PROPOSED_LIMIT")
    #: Потолок времени на весь опрос внешних источников в одном анализе, секунды.
    #:
    #: Не «на всякий случай»: анализ обязан закончиться, а чужой сервер ничего нам не обещал.
    #: Кандидаты, до которых не дошла очередь, считаются по корпусной части и помечены — ровно
    #: как те, о ком источник промолчал.
    signals_budget_seconds: float = Field(default=300.0, alias="HORIZON_SIGNALS_BUDGET_SECONDS")
    #: Потолок на один термин у одного источника, секунды.
    signals_term_budget_seconds: float = Field(
        default=30.0, alias="HORIZON_SIGNALS_TERM_BUDGET_SECONDS"
    )
    #: Не ходить в сеть вообще: признаки берутся только из кэша. Режим воспроизводимого прогона —
    #: те же числа при нулевом трафике.
    signals_cache_only: bool = Field(default=False, alias="HORIZON_SIGNALS_CACHE_ONLY")

    # ── внешняя проверка зрелости ──
    #: Спрашивать ли OpenAlex и Википедию, не массовая ли уже технология из верхушки отчёта.
    #:
    #: Выключено по умолчанию, как и всё, что ходит в сеть из анализа: локальный прогон и
    #: детерминированное QA не должны зависеть от чужих серверов. На стенде включается явно.
    external_maturity_enabled: bool = Field(
        default=False, alias="HORIZON_EXTERNAL_MATURITY_ENABLED"
    )
    #: Сколько секунд проверка вправе потратить на один анализ. Кончился бюджет — непроверенные
    #: темы остаются в отчёте: медленный источник не должен молча выбрасывать темы.
    external_maturity_budget_seconds: float = Field(
        default=120.0, alias="HORIZON_EXTERNAL_MATURITY_BUDGET_SECONDS"
    )
    #: Ключ OpenAlex. Без него суточный бюджет — $0,10 на адрес, сто поисковых запросов, и
    #: проверка зрелости судит только по Википедии: спрашивать OpenAlex о каждой теме значило бы
    #: съесть бюджет, нужный самому сбору. С ключом ($1 в сутки) добавляется правило числа работ.
    openalex_api_key: str = Field(default="", alias="HORIZON_OPENALEX_API_KEY")
    #: Контакт для «вежливого пула» OpenAlex — тот же, что у коннекторов сбора.
    connector_contact_email: str = Field(
        default="ops@horizon.example", alias="HORIZON_CONNECTOR_CONTACT_EMAIL"
    )

    # ── determinism ──
    fixed_clock: str = Field(default="", alias="HORIZON_FIXED_CLOCK")
    """RFC 3339 instant pinning the clock for deterministic QA (ADR-0015). Empty = real."""

    # ── storage ──
    mention_store: MentionStore = Field(default="clickhouse", alias="HORIZON_MENTION_STORE")
    postgres_host: str = Field(default="postgres", alias="POSTGRES_HOST")
    postgres_port: int = Field(default=5432, alias="POSTGRES_PORT")
    postgres_db: str = Field(default="horizon", alias="POSTGRES_DB")
    postgres_user: str = Field(default="analytics_app", alias="ANALYTICS_DB_USER")
    postgres_password: str = Field(default="", alias="ANALYTICS_DB_PASSWORD")
    postgres_schema: str = Field(default="analytics", alias="ANALYTICS_DB_SCHEMA")
    clickhouse_url: str = Field(default="http://clickhouse:8123", alias="CLICKHOUSE_URL")
    clickhouse_user: str = Field(default="horizon", alias="CLICKHOUSE_USER")
    clickhouse_password: str = Field(default="", alias="CLICKHOUSE_PASSWORD")
    clickhouse_database: str = Field(default="horizon", alias="CLICKHOUSE_DATABASE")

    # ── corpus ──
    fixture_corpus_path: str = Field(
        default="/opt/horizon/fixtures/corpus/documents.jsonl",
        alias="HORIZON_FIXTURE_CORPUS_PATH",
    )

    # ── messaging ──
    kafka_bootstrap_servers: str = Field(default="redpanda:9092", alias="KAFKA_BOOTSTRAP_SERVERS")
    kafka_consumer_group_prefix: str = Field(default="horizon", alias="KAFKA_CONSUMER_GROUP_PREFIX")
    kafka_commands_topic: str = Field(
        default="horizon.analysis.commands.v1", alias="HORIZON_ANALYSIS_COMMANDS_TOPIC"
    )
    kafka_events_topic: str = Field(
        default="horizon.analysis.events.v1", alias="HORIZON_ANALYSIS_EVENTS_TOPIC"
    )
    worker_concurrency: int = Field(default=2, alias="HORIZON_ANALYTICS_WORKER_CONCURRENCY")
    #: Сколько анализ вправе держать сообщение, не возвращаясь к опросу очереди, мс.
    #:
    #: Анализ идёт внутри цикла опроса и длится пять–десять минут (пачка до
    #: ``HORIZON_ANALYTICS_WORKER_CONCURRENCY`` команд — по очереди), а умолчание aiokafka — пять:
    #: брокер счёл бы потребителя мёртвым посреди анализа, отдал раздел другому, и команда ушла бы
    #: на повторную доставку — второй анализ того же запроса. 45 минут — с запасом выше саги
    #: качественного режима (40 минут): дольше её анализ ждать некому.
    kafka_max_poll_interval_ms: int = Field(
        default=45 * 60 * 1000, alias="HORIZON_ANALYTICS_MAX_POLL_INTERVAL_MS"
    )
    #: Порт, на котором роль worker отдаёт собственные метрики.
    #:
    #: У роли worker нет HTTP-API, поэтому её метрики — длительность стадий конвейера, число
    #: кандидатов, отсечения фильтров — некому отдать. Prometheus скрёб `analytics-worker:9100`,
    #: а слушателя на этом порту не существовало: самый долгий этап анализа был невидим.
    worker_metrics_port: int = Field(default=9100, alias="HORIZON_METRICS_PORT")
    worker_liveness_file: str = Field(
        default="/tmp/worker-alive",
        alias="HORIZON_WORKER_LIVENESS_FILE",
    )

    @field_validator("log_format")
    @classmethod
    def _validate_log_format(cls, value: str) -> str:
        """Accept only ``json`` or ``console``."""
        lowered = value.lower()
        if lowered not in {"json", "console"}:
            raise ValueError("HORIZON_LOG_FORMAT must be 'json' or 'console'")
        return lowered

    @field_validator("fixed_clock")
    @classmethod
    def _validate_fixed_clock(cls, value: str) -> str:
        """Verify that a pinned clock parses as RFC 3339."""
        if value:
            datetime.fromisoformat(value.replace("Z", "+00:00"))
        return value

    @property
    def json_logs(self) -> bool:
        """Whether logs should be rendered as JSON."""
        return self.log_format == "json"

    @property
    def postgres_dsn(self) -> str:
        """Asyncpg DSN built from the discrete settings."""
        return (
            f"postgresql://{self.postgres_user}:{self.postgres_password}"
            f"@{self.postgres_host}:{self.postgres_port}/{self.postgres_db}"
        )

    @property
    def consumer_group(self) -> str:
        """Kafka consumer group of the analysis worker."""
        return f"{self.kafka_consumer_group_prefix}.analytics"


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    """Process-wide settings singleton."""
    return Settings()
