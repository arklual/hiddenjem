-- =====================================================================================
--  trends-service — начальная схема (docs/01-analysis/04-data-model.md §3).
--
--  Схема на сервис (ADR-0006): кросс-схемные запросы запрещены, поэтому таблицы
--  транзакционного outbox копируются сюда из platform-spring/db/platform/outbox.sql,
--  а не переиспользуются из чужой схемы.
--
--  Соглашения §0: PK — UUIDv7, время — timestamptz (UTC), перечисления — varchar + CHECK,
--  индексы ix_<таблица>_<колонки>, уникальные — ux_.
-- =====================================================================================

-- ─────────────────────────────── research_requests ───────────────────────────────
-- Агрегат и одновременно состояние процесс-менеджера (ADR-0004): всё, что саге нужно
-- знать между шагами, лежит в одной строке — восстановление после рестарта тривиально.
CREATE TABLE research_requests (
    id                     uuid         PRIMARY KEY,
    user_id                uuid         NOT NULL,
    organization_id        uuid         NOT NULL,
    raw_query              varchar(200) NOT NULL,
    normalized_query       varchar(200) NOT NULL,
    query_language         char(2)      NULL,
    top_n                  smallint     NOT NULL CHECK (top_n BETWEEN 5 AND 50),
    years_window           smallint     NOT NULL CHECK (years_window BETWEEN 3 AND 15),
    source_classes         varchar(24)[] NOT NULL DEFAULT '{}',
    min_confidence         numeric(4,3) NOT NULL DEFAULT 0.000,
    include_mature         boolean      NOT NULL DEFAULT false,
    methodology_profile_id uuid         NOT NULL,
    -- Полная идентичность параметров одной строкой: делает поиск актуального отчёта
    -- (BR-A8) индексируемым, не требуя сравнения шести колонок и массива.
    params_discriminator   varchar(160) NOT NULL,
    status                 varchar(16)  NOT NULL CHECK (status IN
                               ('PENDING','COLLECTING','ANALYZING','ASSEMBLING','COMPLETED','FAILED','CANCELLED')),
    progress_stage         varchar(24)  NULL,
    progress_percent       smallint     NOT NULL DEFAULT 0 CHECK (progress_percent BETWEEN 0 AND 100),
    progress_message       varchar(300) NULL,
    progress_updated_at    timestamptz  NOT NULL,
    corpus_snapshot_id     uuid         NULL,
    corpus_document_count  int          NOT NULL DEFAULT 0,
    corpus_sources_used    varchar(48)[] NOT NULL DEFAULT '{}',
    corpus_unavailable     varchar(48)[] NOT NULL DEFAULT '{}',
    analysis_job_id        uuid         NULL,
    report_id              uuid         NULL,
    partial                boolean      NOT NULL DEFAULT false,
    failure_code           varchar(48)  NULL,
    failure_message        text         NULL,
    failure_retryable      boolean      NULL,
    idempotency_key        varchar(80)  NULL,
    attempt                smallint     NOT NULL DEFAULT 1,
    trace_id               varchar(32)  NULL,
    submitted_at           timestamptz  NOT NULL,
    started_at             timestamptz  NULL,
    finished_at            timestamptz  NULL,
    deadline_at            timestamptz  NOT NULL,
    version                bigint       NOT NULL DEFAULT 0
);

-- Идемпотентность POST: повтор с тем же ключом возвращает исходный запрос, а не запускает
-- второй дорогой анализ. Частичный индекс — ключ необязателен.
CREATE UNIQUE INDEX ux_requests_idempotency ON research_requests (user_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;

CREATE INDEX ix_requests_user_time ON research_requests (user_id, submitted_at DESC);

-- Подметатель таймаутов сканирует только живые запросы, поэтому история ему ничего не стоит.
CREATE INDEX ix_requests_active_deadline ON research_requests (deadline_at)
    WHERE status IN ('PENDING','COLLECTING','ANALYZING','ASSEMBLING');

CREATE INDEX ix_requests_lookup
    ON research_requests (normalized_query, top_n, years_window, methodology_profile_id, finished_at DESC)
    WHERE status = 'COMPLETED';

-- Точный поиск «тот же вопрос» = нормализованный запрос + весь набор параметров.
CREATE INDEX ix_requests_cache_lookup
    ON research_requests (normalized_query, params_discriminator, finished_at DESC)
    WHERE status = 'COMPLETED';

-- ───────────────────────────────── trend_reports ─────────────────────────────────
-- Неизменяем (BRULE-5): пересчёт создаёт новую версию со ссылкой на предыдущую.
CREATE TABLE trend_reports (
    id                     uuid         PRIMARY KEY,
    research_request_id    uuid         NOT NULL REFERENCES research_requests (id),
    version                int          NOT NULL DEFAULT 1,
    previous_version_id    uuid         NULL REFERENCES trend_reports (id),
    -- raw_query/query_language: отчёт читается отдельно от запроса, а API обязан вернуть
    -- формулировку пользователя дословно (поле `query` контракта). Без них пришлось бы
    -- показывать нормализованную форму — это порча пользовательского текста.
    raw_query              varchar(200) NOT NULL,
    normalized_query       varchar(200) NOT NULL,
    query_language         char(2)      NULL,
    methodology_version    varchar(24)  NOT NULL,
    methodology_profile_id uuid         NOT NULL,
    score_aggregator       varchar(32)  NOT NULL,
    corpus_snapshot_id     uuid         NOT NULL,
    window_from            date         NOT NULL,
    window_to              date         NOT NULL,
    documents_analyzed     int          NOT NULL,
    candidates_evaluated   int          NOT NULL,
    sources_used           varchar(48)[] NOT NULL,
    unavailable_sources    varchar(48)[] NOT NULL DEFAULT '{}',
    partial                boolean      NOT NULL DEFAULT false,
    truncated              boolean      NOT NULL DEFAULT false,
    generated_at           timestamptz  NOT NULL
);

CREATE INDEX ix_reports_request ON trend_reports (research_request_id, version DESC);

-- ───────────────────────────────── report_trends ─────────────────────────────────
-- indicators/timeline — jsonb: неизменяемые value-объекты, читаются только целиком
-- вместе с трендом и никогда не запрашиваются независимо (§3, обоснование в доке).
CREATE TABLE report_trends (
    report_id              uuid         NOT NULL REFERENCES trend_reports (id) ON DELETE CASCADE,
    rank                   smallint     NOT NULL CHECK (rank >= 1),
    trend_key              varchar(160) NOT NULL,
    title                  varchar(200) NOT NULL,
    definition             text         NOT NULL,
    problem_statement      text         NOT NULL,
    benefit_statement      text         NOT NULL,
    motivation_attribution jsonb        NOT NULL DEFAULT '[]',
    case_org_name          varchar(300) NULL,
    case_org_type          varchar(24)  NULL,
    case_org_country       char(2)      NULL,
    case_evidence_index    smallint     NULL,
    case_summary           text         NULL,
    emergence_score        numeric(9,6) NOT NULL CHECK (emergence_score BETWEEN 0 AND 100),
    confidence             numeric(7,6) NOT NULL CHECK (confidence BETWEEN 0 AND 1),
    low_evidence           boolean      NOT NULL,
    lifecycle_stage        varchar(16)  NOT NULL CHECK (lifecycle_stage IN
                               ('EMBRYONIC','EMERGING','ACCELERATING','MATURING')),
    first_mention_year     smallint     NOT NULL,
    total_documents        int          NOT NULL,
    burst_start_period     varchar(10)  NULL,
    burst_weight           numeric(9,6) NULL,
    indicators             jsonb        NOT NULL,
    timeline               jsonb        NOT NULL,
    PRIMARY KEY (report_id, rank)
);

CREATE UNIQUE INDEX ux_report_trends_key ON report_trends (report_id, trend_key);

-- ────────────────────────────── report_trend_evidence ────────────────────────────
-- Вынесено в таблицу, а не в jsonb: доказательства адресуются отдельно (переходы по
-- источникам, аналитика покрытия) — единственная часть тренда с самостоятельным доступом.
CREATE TABLE report_trend_evidence (
    report_id            uuid         NOT NULL,
    rank                 smallint     NOT NULL,
    ordinal              smallint     NOT NULL,
    source_id            varchar(48)  NOT NULL,
    source_class         varchar(24)  NOT NULL,
    external_id          varchar(200) NOT NULL,
    title                text         NOT NULL,
    authors              varchar(600) NULL,
    organization         varchar(400) NULL,
    organization_country char(2)      NULL,
    published_on         date         NOT NULL,
    url                  text         NOT NULL,
    doi                  varchar(200) NULL,
    citation_count       int          NULL,
    relevance            numeric(7,6) NOT NULL,
    snippet              text         NULL,
    PRIMARY KEY (report_id, rank, ordinal),
    FOREIGN KEY (report_id, rank) REFERENCES report_trends (report_id, rank) ON DELETE CASCADE
);

-- ─────────────────────────────── methodology_profiles ────────────────────────────
CREATE TABLE methodology_profiles (
    id                   uuid         PRIMARY KEY,
    name                 varchar(80)  NOT NULL,
    version              int          NOT NULL,
    methodology_version  varchar(24)  NOT NULL,
    aggregator           varchar(32)  NOT NULL DEFAULT 'WEIGHTED_GEOMETRIC',
    weights              jsonb        NOT NULL,
    parameters           jsonb        NOT NULL,
    confidence_threshold numeric(4,3) NOT NULL DEFAULT 0.400,
    is_default           boolean      NOT NULL DEFAULT false,
    created_by           uuid         NULL,
    created_at           timestamptz  NOT NULL
);

CREATE UNIQUE INDEX ux_profiles_name_version ON methodology_profiles (name, version);

-- Ровно один профиль по умолчанию — инвариант держит база, а не соглашение в коде.
CREATE UNIQUE INDEX ux_profiles_single_default ON methodology_profiles (is_default) WHERE is_default;

-- ──────────────────────────────────── saved_domains ──────────────────────────────
CREATE TABLE saved_domains (
    id               uuid         PRIMARY KEY,
    user_id          uuid         NOT NULL,
    raw_query        varchar(200) NOT NULL,
    normalized_query varchar(200) NOT NULL,
    query_language   char(2)      NULL,
    parameters       jsonb        NOT NULL,
    last_report_id   uuid         NULL,
    created_at       timestamptz  NOT NULL
);

CREATE UNIQUE INDEX ux_saved_domains_user_query ON saved_domains (user_id, normalized_query);

-- ──────────────────────────────────── trend_feedback ─────────────────────────────
CREATE TABLE trend_feedback (
    id         uuid         PRIMARY KEY,
    user_id    uuid         NOT NULL,
    report_id  uuid         NOT NULL,
    trend_key  varchar(160) NOT NULL,
    verdict    varchar(16)  NOT NULL CHECK (verdict IN ('RELEVANT','NOISE','ALREADY_KNOWN')),
    comment    text         NULL,
    created_at timestamptz  NOT NULL
);

-- Одна оценка пользователя на тренд: повторный PUT перезаписывает, а не накапливает.
CREATE UNIQUE INDEX ux_feedback_user_trend ON trend_feedback (user_id, report_id, trend_key);

-- =====================================================================================
--  Платформенные таблицы — копия platform-spring/src/main/resources/db/platform/outbox.sql
-- =====================================================================================

CREATE TABLE outbox_messages (
    id              uuid         PRIMARY KEY,
    aggregate_type  varchar(64)  NOT NULL,
    aggregate_id    varchar(64)  NOT NULL,
    event_type      varchar(96)  NOT NULL,
    topic           varchar(120) NOT NULL,
    partition_key   varchar(120) NOT NULL,
    payload         text         NOT NULL,
    headers         text         NOT NULL DEFAULT '{}',
    created_at      timestamptz  NOT NULL,
    published_at    timestamptz  NULL,
    attempts        smallint     NOT NULL DEFAULT 0,
    next_attempt_at timestamptz  NOT NULL DEFAULT now(),
    last_error      text         NULL
);

-- Частичный индекс: поллер сканирует только недоставленные строки.
CREATE INDEX ix_outbox_unpublished ON outbox_messages (next_attempt_at)
    WHERE published_at IS NULL;

CREATE TABLE processed_messages (
    consumer     varchar(64) NOT NULL,
    message_id   varchar(64) NOT NULL,
    processed_at timestamptz NOT NULL,
    PRIMARY KEY (consumer, message_id)
);

CREATE INDEX ix_processed_messages_time ON processed_messages (processed_at);
