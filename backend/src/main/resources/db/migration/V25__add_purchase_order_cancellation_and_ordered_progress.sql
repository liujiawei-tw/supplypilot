ALTER TABLE purchase_orders
    ADD COLUMN ordered_progress VARCHAR(30) NULL,
    ADD COLUMN cancel_reason TEXT NULL,
    ADD COLUMN cancelled_by BIGINT NULL,
    ADD COLUMN cancelled_at DATETIME(6) NULL,
    ADD COLUMN cancelled_by_user_id_snapshot BIGINT NULL,
    ADD COLUMN cancelled_by_username_snapshot VARCHAR(50) NULL,
    ADD COLUMN cancelled_by_display_name_snapshot VARCHAR(100) NULL;

-- Existing ORDERED rows have no durable evidence that distinguishes follow-up from
-- receiving inspection. Keep them explicitly unknown and block cancellation safely.
UPDATE purchase_orders SET ordered_progress = 'LEGACY_UNKNOWN' WHERE status = 'ORDERED';

ALTER TABLE purchase_orders
    DROP CHECK chk_purchase_orders_status,
    ADD CONSTRAINT chk_purchase_orders_status
        CHECK (status IN ('DRAFT', 'APPROVED', 'ORDERED', 'RECEIVED', 'REJECTED', 'CANCELLED')),
    ADD CONSTRAINT chk_purchase_orders_ordered_progress
        CHECK ((status = 'ORDERED' AND ordered_progress IS NOT NULL
                AND ordered_progress IN ('FOLLOW_UP', 'RECEIVING_INSPECTION', 'LEGACY_UNKNOWN'))
            OR (status <> 'ORDERED' AND ordered_progress IS NULL)),
    ADD CONSTRAINT fk_purchase_orders_cancelled_by
        FOREIGN KEY (cancelled_by) REFERENCES users(id) ON DELETE SET NULL,
    ADD INDEX idx_purchase_orders_ordered_progress (ordered_progress);
