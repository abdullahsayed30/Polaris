--liquibase formatted sql

--changeset codex:0005-durable-reservation-recovery
ALTER TABLE orders ADD COLUMN reservation_retry_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;
CREATE INDEX idx_orders_pending_reservation ON orders (reservation_retry_at, id) WHERE status = 'PENDING';
--rollback DROP INDEX idx_orders_pending_reservation;
--rollback ALTER TABLE orders DROP COLUMN reservation_retry_at;
