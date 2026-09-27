--liquibase formatted sql

--changeset codex:0002-create-inventory-reservations
CREATE TABLE inventory_reservations (
    order_id UUID PRIMARY KEY,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT ck_inventory_reservations_status
        CHECK (status IN ('PROCESSING', 'RESERVED', 'REJECTED', 'RELEASED'))
);

CREATE TABLE inventory_reservation_lines (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL,
    sku VARCHAR(128) NOT NULL,
    requested_quantity INTEGER NOT NULL,
    reserved_quantity INTEGER NOT NULL,
    remaining_quantity INTEGER NOT NULL,
    released_available_quantity INTEGER,
    CONSTRAINT fk_inventory_reservation_lines_order_id
        FOREIGN KEY (order_id)
        REFERENCES inventory_reservations (order_id)
        ON DELETE CASCADE,
    CONSTRAINT uq_inventory_reservation_lines_order_sku UNIQUE (order_id, sku),
    CONSTRAINT ck_inventory_reservation_lines_requested_positive CHECK (requested_quantity > 0),
    CONSTRAINT ck_inventory_reservation_lines_reserved_non_negative CHECK (reserved_quantity >= 0),
    CONSTRAINT ck_inventory_reservation_lines_remaining_non_negative CHECK (remaining_quantity >= 0),
    CONSTRAINT ck_inventory_reservation_lines_released_non_negative
        CHECK (released_available_quantity IS NULL OR released_available_quantity >= 0),
    CONSTRAINT ck_inventory_reservation_lines_reserved_not_above_requested
        CHECK (reserved_quantity <= requested_quantity)
);

CREATE INDEX idx_inventory_reservation_lines_sku ON inventory_reservation_lines (sku);

--rollback DROP TABLE IF EXISTS inventory_reservation_lines; DROP TABLE IF EXISTS inventory_reservations;
