--liquibase formatted sql

--changeset codex:0001-create-notification-inbox-events
CREATE TABLE inbox_events (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(128) NOT NULL,
    event_version INTEGER NOT NULL,
    source_topic VARCHAR(255) NOT NULL,
    source_partition INTEGER NOT NULL,
    source_offset BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    received_at TIMESTAMPTZ NOT NULL,
    processed_at TIMESTAMPTZ,
    last_error VARCHAR(2000),
    CONSTRAINT uq_notification_inbox_source UNIQUE (source_topic, source_partition, source_offset),
    CONSTRAINT ck_notification_inbox_event_version_positive CHECK (event_version > 0),
    CONSTRAINT ck_notification_inbox_status CHECK (status IN ('PROCESSING', 'PROCESSED', 'DEAD_LETTERED'))
);

CREATE INDEX idx_notification_inbox_status ON inbox_events (status, received_at);

--rollback DROP TABLE IF EXISTS inbox_events;
