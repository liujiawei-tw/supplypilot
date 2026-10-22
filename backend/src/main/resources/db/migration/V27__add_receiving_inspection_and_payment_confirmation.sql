ALTER TABLE purchase_orders
    ADD COLUMN inspection_all_items_received BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN inspection_all_quantities_confirmed BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN inspection_all_quality_accepted BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN inspection_note TEXT NULL,
    ADD COLUMN inspected_by BIGINT NULL,
    ADD COLUMN inspected_by_user_id_snapshot BIGINT NULL,
    ADD COLUMN inspected_by_username_snapshot VARCHAR(50) NULL,
    ADD COLUMN inspected_by_display_name_snapshot VARCHAR(100) NULL,
    ADD COLUMN inspected_at DATETIME(6) NULL,
    ADD COLUMN payment_confirmed_by BIGINT NULL,
    ADD COLUMN payment_confirmed_by_user_id_snapshot BIGINT NULL,
    ADD COLUMN payment_confirmed_by_username_snapshot VARCHAR(50) NULL,
    ADD COLUMN payment_confirmed_by_display_name_snapshot VARCHAR(100) NULL,
    ADD COLUMN payment_confirmed_at DATETIME(6) NULL,
    ADD CONSTRAINT fk_purchase_orders_inspected_by
        FOREIGN KEY (inspected_by) REFERENCES users(id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_purchase_orders_payment_confirmed_by
        FOREIGN KEY (payment_confirmed_by) REFERENCES users(id) ON DELETE SET NULL,
    ADD CONSTRAINT chk_purchase_orders_receiving_inspection_audit
        CHECK ((inspected_at IS NULL AND inspected_by_user_id_snapshot IS NULL
                    AND inspected_by_username_snapshot IS NULL AND inspected_by_display_name_snapshot IS NULL
                    AND inspection_all_items_received = FALSE AND inspection_all_quantities_confirmed = FALSE
                    AND inspection_all_quality_accepted = FALSE AND inspection_note IS NULL)
            OR (inspected_at IS NOT NULL AND inspected_by_user_id_snapshot IS NOT NULL
                    AND inspected_by_username_snapshot IS NOT NULL AND inspected_by_display_name_snapshot IS NOT NULL)),
    ADD CONSTRAINT chk_purchase_orders_payment_confirmation
        CHECK ((payment_confirmed_at IS NULL
                    AND payment_confirmed_by_user_id_snapshot IS NULL
                    AND payment_confirmed_by_username_snapshot IS NULL
                    AND payment_confirmed_by_display_name_snapshot IS NULL)
            OR (status = 'RECEIVED' AND payment_confirmed_at IS NOT NULL
                    AND payment_confirmed_by_user_id_snapshot IS NOT NULL
                    AND payment_confirmed_by_username_snapshot IS NOT NULL
                    AND payment_confirmed_by_display_name_snapshot IS NOT NULL));
