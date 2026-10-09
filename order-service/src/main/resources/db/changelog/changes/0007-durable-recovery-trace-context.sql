--liquibase formatted sql

--changeset abdullahsayed30:0007-durable-recovery-trace-context
ALTER TABLE orders ADD COLUMN recovery_trace_context TEXT;
