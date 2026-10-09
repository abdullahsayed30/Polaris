--liquibase formatted sql

--changeset abdullahsayed30:0008-durable-outbox-trace-context
ALTER TABLE outbox_events ADD COLUMN trace_context TEXT;
