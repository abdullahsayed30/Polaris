--liquibase formatted sql

--changeset abdullahsayed30:0001-create-orders
CREATE TABLE orders (
    id UUID NOT NULL,
    customer_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT pk_orders PRIMARY KEY (id)
);

CREATE INDEX idx_orders_customer_id ON orders (customer_id);
CREATE INDEX idx_orders_status ON orders (status);
--rollback DROP TABLE IF EXISTS orders;
