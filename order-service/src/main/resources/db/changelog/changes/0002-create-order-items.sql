--liquibase formatted sql

--changeset abdullahsayed30:0002-create-order-items
CREATE TABLE order_items (
    id UUID NOT NULL,
    order_id UUID NOT NULL,
    sku VARCHAR(128) NOT NULL,
    quantity INTEGER NOT NULL,
    unit_price NUMERIC(19, 2) NOT NULL,
    CONSTRAINT pk_order_items PRIMARY KEY (id),
    CONSTRAINT fk_order_items_order_id
        FOREIGN KEY (order_id)
        REFERENCES orders (id)
        ON DELETE CASCADE
);

CREATE INDEX idx_order_items_order_id ON order_items (order_id);
CREATE INDEX idx_order_items_sku ON order_items (sku);
--rollback DROP TABLE IF EXISTS order_items;
