-- Shared DDL fragment for the transactional outbox and consumer deduplication.
-- Each service copies this into its own Flyway migration because the tables live in the
-- service's own schema (ADR-0006: no cross-schema access).

CREATE TABLE outbox_messages (
    id              uuid        PRIMARY KEY,
    aggregate_type  varchar(64) NOT NULL,
    aggregate_id    varchar(64) NOT NULL,
    event_type      varchar(96) NOT NULL,
    topic           varchar(120) NOT NULL,
    partition_key   varchar(120) NOT NULL,
    payload         text        NOT NULL,
    headers         text        NOT NULL DEFAULT '{}',
    created_at      timestamptz NOT NULL,
    published_at    timestamptz NULL,
    attempts        smallint    NOT NULL DEFAULT 0,
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    last_error      text        NULL
);

-- Partial index: the poller only ever scans undelivered rows, so delivered history costs nothing.
CREATE INDEX ix_outbox_unpublished ON outbox_messages (next_attempt_at)
    WHERE published_at IS NULL;

CREATE TABLE processed_messages (
    consumer     varchar(64) NOT NULL,
    message_id   varchar(64) NOT NULL,
    processed_at timestamptz NOT NULL,
    PRIMARY KEY (consumer, message_id)
);

CREATE INDEX ix_processed_messages_time ON processed_messages (processed_at);
