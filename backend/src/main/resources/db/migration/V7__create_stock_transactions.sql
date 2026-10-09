CREATE TABLE stock_transactions (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    material_id BIGINT NOT NULL,
    type VARCHAR(30) NOT NULL,
    quantity DECIMAL(19,4) NOT NULL,
    purchase_order_id BIGINT NULL,
    reason VARCHAR(500) NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_stock_transactions_material FOREIGN KEY (material_id) REFERENCES materials(id),
    CONSTRAINT fk_stock_transactions_created_by FOREIGN KEY (created_by) REFERENCES users(id),
    CONSTRAINT chk_stock_transactions_type CHECK (type IN ('PURCHASE_RECEIVE', 'MANUAL_ADJUSTMENT')),
    CONSTRAINT chk_stock_transactions_quantity_nonzero CHECK (quantity <> 0),
    CONSTRAINT chk_stock_transactions_type_fields CHECK (
        (type = 'PURCHASE_RECEIVE' AND purchase_order_id IS NOT NULL)
        OR (type = 'MANUAL_ADJUSTMENT' AND purchase_order_id IS NULL AND reason IS NOT NULL AND CHAR_LENGTH(TRIM(reason)) > 0)
    ),
    INDEX idx_stock_transactions_material_created_at (material_id, created_at),
    INDEX idx_stock_transactions_purchase_order (purchase_order_id),
    INDEX idx_stock_transactions_type (type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
