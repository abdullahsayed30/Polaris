--liquibase formatted sql

--changeset codex:0003-create-order-outbox-events
CREATE TABLE outbox_events (
    event_id UUID PRIMARY KEY,
    aggregate_id VARCHAR(128) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    event_version INTEGER NOT NULL,
    topic VARCHAR(255) NOT NULL,
    message_key VARCHAR(255) NOT NULL,
    payload TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(32) NOT NULL,
    attempts INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    last_error VARCHAR(2000),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_order_outbox_event_version_positive CHECK (event_version > 0),
    CONSTRAINT ck_order_outbox_attempts_non_negative CHECK (attempts >= 0),
    CONSTRAINT ck_order_outbox_status CHECK (status IN ('PENDING', 'RETRY', 'PUBLISHED', 'FAILED'))
);

CREATE INDEX idx_order_outbox_ready ON outbox_events (status, next_attempt_at);

--rollback DROP TABLE IF EXISTS outbox_events;
