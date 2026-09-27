--liquibase formatted sql

--changeset codex:0006-bind-processing-order-requests
ALTER TABLE order_requests DROP CONSTRAINT ck_order_requests_completion;
ALTER TABLE order_requests ADD CONSTRAINT ck_order_requests_completion
    CHECK (status = 'PROCESSING' OR (status = 'COMPLETED' AND order_id IS NOT NULL));

--rollback ALTER TABLE order_requests DROP CONSTRAINT ck_order_requests_completion;
--rollback ALTER TABLE order_requests ADD CONSTRAINT ck_order_requests_completion CHECK ((status = 'PROCESSING' AND order_id IS NULL) OR (status = 'COMPLETED' AND order_id IS NOT NULL));
