ALTER TABLE purchase_order_items
    ADD COLUMN currency VARCHAR(10) NULL;

UPDATE purchase_order_items
SET currency = 'TWD'
WHERE currency IS NULL;

ALTER TABLE purchase_order_items
    MODIFY COLUMN currency VARCHAR(10) NOT NULL,
    ADD CONSTRAINT chk_purchase_order_items_currency CHECK (currency = 'TWD');
