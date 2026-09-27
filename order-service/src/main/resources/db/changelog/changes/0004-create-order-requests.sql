--liquibase formatted sql

--changeset codex:0004-create-order-requests
CREATE TABLE order_requests (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    order_id UUID,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_order_requests_customer_key UNIQUE (customer_id, idempotency_key),
    CONSTRAINT uq_order_requests_order_id UNIQUE (order_id),
    CONSTRAINT fk_order_requests_order_id FOREIGN KEY (order_id) REFERENCES orders (id),
    CONSTRAINT ck_order_requests_request_hash_length CHECK (length(request_hash) = 64),
    CONSTRAINT ck_order_requests_status CHECK (status IN ('PROCESSING', 'COMPLETED')),
    CONSTRAINT ck_order_requests_completion
        CHECK ((status = 'PROCESSING' AND order_id IS NULL) OR (status = 'COMPLETED' AND order_id IS NOT NULL))
);

--rollback DROP TABLE IF EXISTS order_requests;
