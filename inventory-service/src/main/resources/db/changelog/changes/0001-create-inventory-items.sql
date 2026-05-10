--liquibase formatted sql

--changeset abdullahsayed30:0001-create-inventory-items
CREATE TABLE inventory_items (
    id UUID PRIMARY KEY,
    sku VARCHAR(128) NOT NULL,
    available_quantity INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_inventory_items_sku UNIQUE (sku),
    CONSTRAINT ck_inventory_items_available_quantity_non_negative CHECK (available_quantity >= 0)
);

--rollback DROP TABLE IF EXISTS inventory_items;
