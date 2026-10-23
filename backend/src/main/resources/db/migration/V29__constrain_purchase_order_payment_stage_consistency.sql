ALTER TABLE purchase_orders
    ADD CONSTRAINT chk_purchase_orders_received_payment_stage
        CHECK (
            status <> 'RECEIVED'
            OR (stage IS NOT NULL
                AND ((stage = 'PAYMENT_RECONCILIATION' AND payment_confirmed_at IS NULL)
                    OR (stage = 'COMPLETED' AND payment_confirmed_at IS NOT NULL)))
        );
