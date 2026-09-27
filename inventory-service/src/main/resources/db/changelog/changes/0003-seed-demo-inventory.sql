--liquibase formatted sql

--changeset polaris:0003-seed-demo-inventory context:demo
INSERT INTO inventory_items (id, sku, available_quantity, created_at, updated_at, version)
VALUES
    ('c0ffee00-0000-4000-8000-000000000001', 'SKU-COFFEE-001', 100, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0),
    ('c0ffee00-0000-4000-8000-000000000002', 'SKU-MUG-002', 100, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0);

--rollback DELETE FROM inventory_items WHERE id IN ('c0ffee00-0000-4000-8000-000000000001', 'c0ffee00-0000-4000-8000-000000000002');
