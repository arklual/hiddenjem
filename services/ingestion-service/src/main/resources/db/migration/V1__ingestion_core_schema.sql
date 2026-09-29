-- Schema `ingestion`: canonical documents, source registry, run history and cursors.
-- Mirrors docs/01-analysis/04-data-model.md §2.

CREATE TABLE sources (
    id                    varchar(48)  PRIMARY KEY,
    display_name          varchar(120) NOT NULL,
    source_class          varchar(24)  NOT NULL
        CHECK (source_class IN ('PREPRINT','JOURNAL_ARTICLE','PATENT','CODE_REPOSITORY',
                                'NEWS','ANALYST_REPORT','STANDARD')),
    enabled               boolean      NOT NULL DEFAULT true,
    base_url              varchar(400) NOT NULL,
    rate_limit_per_minute int          NOT NULL DEFAULT 30 CHECK (rate_limit_per_minute BETWEEN 1 AND 6000),
    requires_api_key      boolean      NOT NULL DEFAULT false,
    config                jsonb        NOT NULL DEFAULT '{}'::jsonb,
    authority_weight      numeric(4,3) NOT NULL DEFAULT 0.500 CHECK (authority_weight BETWEEN 0 AND 1),
    created_at            timestamptz  NOT NULL DEFAULT now(),
    updated_at            timestamptz  NOT NULL DEFAULT now(),
    version               bigint       NOT NULL DEFAULT 0
);

CREATE TABLE source_cursors (
    source_id         varchar(48) PRIMARY KEY REFERENCES sources(id) ON DELETE CASCADE,
    cursor_value      text        NULL,
    last_published_on date        NULL,
    updated_at        timestamptz NOT NULL DEFAULT now(),
    version           bigint      NOT NULL DEFAULT 0
);

CREATE TABLE ingestion_runs (
    id                  uuid        PRIMARY KEY,
    source_id           varchar(48) NOT NULL REFERENCES sources(id),
    mode                varchar(16) NOT NULL CHECK (mode IN ('INCREMENTAL','BACKFILL','ON_DEMAND')),
    research_request_id uuid        NULL,
    query               text        NULL,
    window_from         date        NULL,
    window_to           date        NULL,
    status              varchar(16) NOT NULL CHECK (status IN ('RUNNING','COMPLETED','PARTIAL','FAILED')),
    cursor_before       text        NULL,
    cursor_before_date  date        NULL,
    cursor_after        text        NULL,
    cursor_after_date   date        NULL,
    documents_fetched   int         NOT NULL DEFAULT 0,
    documents_created   int         NOT NULL DEFAULT 0,
    documents_duplicate int         NOT NULL DEFAULT 0,
    documents_rejected  int         NOT NULL DEFAULT 0,
    error_code          varchar(48) NULL,
    error_message       text        NULL,
    started_at          timestamptz NOT NULL,
    finished_at         timestamptz NULL,
    trace_id            varchar(32) NULL
);

CREATE INDEX ix_runs_source_started ON ingestion_runs (source_id, started_at DESC);
CREATE INDEX ix_runs_request        ON ingestion_runs (research_request_id) WHERE research_request_id IS NOT NULL;
-- Supports "is a run already in flight for this source?" without scanning history.
CREATE INDEX ix_runs_running        ON ingestion_runs (source_id, started_at) WHERE status = 'RUNNING';

-- Documents are partitioned by publication date: the analysis window is always a date range, so
-- every query prunes to a handful of partitions, and ageing the corpus out is a DETACH rather than
-- a mass DELETE. Uniqueness constraints therefore include the partition key, as PostgreSQL requires.
CREATE TABLE documents (
    id             uuid         NOT NULL,
    source_id      varchar(48)  NOT NULL,
    external_id    varchar(200) NOT NULL,
    source_class   varchar(24)  NOT NULL,
    title          text         NOT NULL,
    abstract_text  text         NULL,
    language       char(2)      NULL,
    published_on   date         NOT NULL,
    doi            varchar(200) NULL,
    arxiv_id       varchar(40)  NULL,
    patent_number  varchar(40)  NULL,
    url            text         NOT NULL,
    venue_name     varchar(300) NULL,
    venue_type     varchar(24)  NULL,
    venue_issn     varchar(20)  NULL,
    citation_count int          NULL,
    extra_metrics  jsonb        NOT NULL DEFAULT '{}'::jsonb,
    dedup_key      char(64)     NOT NULL,
    fetched_at     timestamptz  NOT NULL,
    request_url    text         NOT NULL,
    http_status    smallint     NULL,
    payload_hash   char(64)     NOT NULL,
    raw_ref        varchar(400) NULL,
    version        int          NOT NULL DEFAULT 0,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    search_vector  tsvector GENERATED ALWAYS AS (
        setweight(to_tsvector('simple', coalesce(title, '')), 'A') ||
        setweight(to_tsvector('simple', coalesce(abstract_text, '')), 'B')
    ) STORED,
    PRIMARY KEY (id, published_on),
    CONSTRAINT ux_documents_source_external UNIQUE (source_id, external_id, published_on),
    CONSTRAINT ux_documents_dedup           UNIQUE (dedup_key, published_on)
) PARTITION BY RANGE (published_on);

CREATE TABLE documents_2018 PARTITION OF documents FOR VALUES FROM ('2018-01-01') TO ('2019-01-01');
CREATE TABLE documents_2019 PARTITION OF documents FOR VALUES FROM ('2019-01-01') TO ('2020-01-01');
CREATE TABLE documents_2020 PARTITION OF documents FOR VALUES FROM ('2020-01-01') TO ('2021-01-01');
CREATE TABLE documents_2021 PARTITION OF documents FOR VALUES FROM ('2021-01-01') TO ('2022-01-01');
CREATE TABLE documents_2022 PARTITION OF documents FOR VALUES FROM ('2022-01-01') TO ('2023-01-01');
CREATE TABLE documents_2023 PARTITION OF documents FOR VALUES FROM ('2023-01-01') TO ('2024-01-01');
CREATE TABLE documents_2024 PARTITION OF documents FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');
CREATE TABLE documents_2025 PARTITION OF documents FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE documents_2026 PARTITION OF documents FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
-- Anything outside the known range still lands somewhere instead of failing the insert; an alert
-- on a non-empty default partition tells operations to add the missing year.
CREATE TABLE documents_default PARTITION OF documents DEFAULT;

CREATE INDEX ix_documents_published ON documents (published_on DESC);
CREATE INDEX ix_documents_search    ON documents USING gin (search_vector);
CREATE INDEX ix_documents_doi       ON documents (doi) WHERE doi IS NOT NULL;
CREATE INDEX ix_documents_source    ON documents (source_id, published_on DESC);

CREATE TABLE document_authors (
    document_id         uuid         NOT NULL,
    published_on        date         NOT NULL,
    ordinal             smallint     NOT NULL,
    full_name           varchar(300) NOT NULL,
    orcid               varchar(24)  NULL,
    organization_name   varchar(400) NULL,
    organization_type   varchar(24)  NULL
        CHECK (organization_type IS NULL OR organization_type IN
               ('COMPANY','UNIVERSITY','RESEARCH_INSTITUTE','GOVERNMENT','NONPROFIT')),
    organization_country char(2)     NULL,
    PRIMARY KEY (document_id, published_on, ordinal),
    FOREIGN KEY (document_id, published_on) REFERENCES documents (id, published_on) ON DELETE CASCADE
) PARTITION BY RANGE (published_on);

CREATE TABLE document_authors_2018 PARTITION OF document_authors FOR VALUES FROM ('2018-01-01') TO ('2019-01-01');
CREATE TABLE document_authors_2019 PARTITION OF document_authors FOR VALUES FROM ('2019-01-01') TO ('2020-01-01');
CREATE TABLE document_authors_2020 PARTITION OF document_authors FOR VALUES FROM ('2020-01-01') TO ('2021-01-01');
CREATE TABLE document_authors_2021 PARTITION OF document_authors FOR VALUES FROM ('2021-01-01') TO ('2022-01-01');
CREATE TABLE document_authors_2022 PARTITION OF document_authors FOR VALUES FROM ('2022-01-01') TO ('2023-01-01');
CREATE TABLE document_authors_2023 PARTITION OF document_authors FOR VALUES FROM ('2023-01-01') TO ('2024-01-01');
CREATE TABLE document_authors_2024 PARTITION OF document_authors FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');
CREATE TABLE document_authors_2025 PARTITION OF document_authors FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE document_authors_2026 PARTITION OF document_authors FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
CREATE TABLE document_authors_default PARTITION OF document_authors DEFAULT;

CREATE INDEX ix_doc_authors_org ON document_authors (organization_name)
    WHERE organization_name IS NOT NULL;

CREATE TABLE document_topics_raw (
    document_id  uuid        NOT NULL,
    published_on date        NOT NULL,
    code         varchar(64) NOT NULL,
    label        varchar(200) NULL,
    score        numeric(5,4) NULL,
    PRIMARY KEY (document_id, published_on, code),
    FOREIGN KEY (document_id, published_on) REFERENCES documents (id, published_on) ON DELETE CASCADE
) PARTITION BY RANGE (published_on);

CREATE TABLE document_topics_raw_2018 PARTITION OF document_topics_raw FOR VALUES FROM ('2018-01-01') TO ('2019-01-01');
CREATE TABLE document_topics_raw_2019 PARTITION OF document_topics_raw FOR VALUES FROM ('2019-01-01') TO ('2020-01-01');
CREATE TABLE document_topics_raw_2020 PARTITION OF document_topics_raw FOR VALUES FROM ('2020-01-01') TO ('2021-01-01');
CREATE TABLE document_topics_raw_2021 PARTITION OF document_topics_raw FOR VALUES FROM ('2021-01-01') TO ('2022-01-01');
CREATE TABLE document_topics_raw_2022 PARTITION OF document_topics_raw FOR VALUES FROM ('2022-01-01') TO ('2023-01-01');
CREATE TABLE document_topics_raw_2023 PARTITION OF document_topics_raw FOR VALUES FROM ('2023-01-01') TO ('2024-01-01');
CREATE TABLE document_topics_raw_2024 PARTITION OF document_topics_raw FOR VALUES FROM ('2024-01-01') TO ('2025-01-01');
CREATE TABLE document_topics_raw_2025 PARTITION OF document_topics_raw FOR VALUES FROM ('2025-01-01') TO ('2026-01-01');
CREATE TABLE document_topics_raw_2026 PARTITION OF document_topics_raw FOR VALUES FROM ('2026-01-01') TO ('2027-01-01');
CREATE TABLE document_topics_raw_default PARTITION OF document_topics_raw DEFAULT;

-- Snapshots pin an analysis to an exact set of documents, which is what makes a result reproducible
-- (ADR-0015). They are immutable once written.
CREATE TABLE corpus_snapshots (
    id                  uuid         PRIMARY KEY,
    research_request_id uuid         NOT NULL,
    attempt             smallint     NOT NULL DEFAULT 1,
    normalized_query    varchar(200) NOT NULL,
    window_from         date         NOT NULL,
    window_to           date         NOT NULL,
    document_count      int          NOT NULL,
    sources_used        varchar(48)[] NOT NULL DEFAULT '{}',
    unavailable_sources varchar(48)[] NOT NULL DEFAULT '{}',
    partial             boolean      NOT NULL DEFAULT false,
    content_hash        char(64)     NOT NULL,
    created_at          timestamptz  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX ux_snapshots_request_attempt ON corpus_snapshots (research_request_id, attempt);
CREATE INDEX ix_snapshots_query ON corpus_snapshots (normalized_query, created_at DESC);

CREATE TABLE corpus_snapshot_documents (
    snapshot_id uuid NOT NULL REFERENCES corpus_snapshots(id) ON DELETE CASCADE,
    document_id uuid NOT NULL,
    ordinal     int  NOT NULL,
    PRIMARY KEY (snapshot_id, document_id)
);
