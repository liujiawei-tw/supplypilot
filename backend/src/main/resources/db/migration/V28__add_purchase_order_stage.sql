ALTER TABLE purchase_orders
    ADD COLUMN stage VARCHAR(32) NULL;

-- A DRAFT has not crossed any lifecycle boundary, so initialize every existing
-- draft to the first stage without inferring quotation or approval work.
UPDATE purchase_orders
SET stage = 'NEW_REQUEST'
WHERE status = 'DRAFT';

UPDATE purchase_orders
SET stage = 'APPROVED_PENDING_ORDER'
WHERE status = 'APPROVED';

-- V25 deliberately retained LEGACY_UNKNOWN when the old data could not show
-- whether the PO was in follow-up or inspection. Keep those rows out of the
-- active eight-stage board until an ADMIN explicitly dispositions them through
-- begin-receiving-inspection; do not infer a historical stage.
UPDATE purchase_orders
SET stage = CASE ordered_progress
    WHEN 'FOLLOW_UP' THEN 'ORDER_FOLLOW_UP'
    WHEN 'RECEIVING_INSPECTION' THEN 'RECEIVING_INSPECTION'
    ELSE NULL
END
WHERE status = 'ORDERED';

-- V27 records an explicit payment-confirmation fact. Only that fact separates
-- the two RECEIVED stages; historical RECEIVED rows without it remain stage 7.
UPDATE purchase_orders
SET stage = CASE WHEN payment_confirmed_at IS NULL THEN 'PAYMENT_RECONCILIATION' ELSE 'COMPLETED' END
WHERE status = 'RECEIVED';

-- Terminal orders are excluded from the active eight-stage board.
UPDATE purchase_orders
SET stage = NULL
WHERE status IN ('REJECTED', 'CANCELLED');

ALTER TABLE purchase_orders
    ADD CONSTRAINT chk_purchase_orders_stage_status
        CHECK (
            (status = 'DRAFT' AND stage IS NOT NULL
                AND stage IN ('NEW_REQUEST', 'SOURCING', 'APPROVAL'))
            OR (status = 'APPROVED' AND stage IS NOT NULL AND stage = 'APPROVED_PENDING_ORDER')
            OR (status = 'ORDERED' AND (
                (ordered_progress = 'FOLLOW_UP' AND stage IS NOT NULL AND stage = 'ORDER_FOLLOW_UP')
                OR (ordered_progress = 'RECEIVING_INSPECTION' AND stage IS NOT NULL
                    AND stage = 'RECEIVING_INSPECTION')
                OR (ordered_progress = 'LEGACY_UNKNOWN' AND stage IS NULL)
            ))
            OR (status = 'RECEIVED' AND stage IS NOT NULL
                AND stage IN ('PAYMENT_RECONCILIATION', 'COMPLETED'))
            OR (status IN ('REJECTED', 'CANCELLED') AND stage IS NULL)
        ),
    ADD INDEX idx_purchase_orders_stage (stage);
