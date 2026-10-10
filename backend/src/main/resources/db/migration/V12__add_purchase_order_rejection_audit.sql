ALTER TABLE purchase_orders
    ADD COLUMN rejected_by BIGINT NULL AFTER approved_at,
    ADD COLUMN rejected_at DATETIME(6) NULL AFTER rejected_by,
    ADD CONSTRAINT fk_purchase_orders_rejected_by
        FOREIGN KEY (rejected_by) REFERENCES users(id),
    ADD INDEX idx_purchase_orders_rejected_by (rejected_by);
