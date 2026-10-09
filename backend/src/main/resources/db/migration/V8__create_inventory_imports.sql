CREATE TABLE inventory_import_batches (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    filename VARCHAR(255) NOT NULL,
    uploaded_by BIGINT NOT NULL,
    uploaded_at DATETIME(6) NOT NULL,
    row_count INT NOT NULL,
    status VARCHAR(30) NOT NULL,
    error_summary VARCHAR(1000) NULL,
    CONSTRAINT fk_inventory_import_batches_uploaded_by FOREIGN KEY (uploaded_by) REFERENCES users(id),
    CONSTRAINT chk_inventory_import_batches_row_count_nonnegative CHECK (row_count >= 0),
    CONSTRAINT chk_inventory_import_batches_status CHECK (status IN ('UPLOADED', 'VALIDATED', 'VALIDATION_FAILED', 'IMPORTED', 'IMPORT_FAILED')),
    INDEX idx_inventory_import_batches_uploaded_by (uploaded_by)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE inventory_import_items (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_id BIGINT NOT NULL,
    csv_row_number INT NOT NULL,
    material_id BIGINT NULL,
    material_code_snapshot VARCHAR(255) NULL,
    quantity DECIMAL(19,4) NULL,
    unit VARCHAR(20) NULL,
    valid BOOLEAN NOT NULL,
    error_code VARCHAR(50) NULL,
    error_message VARCHAR(500) NULL,
    CONSTRAINT fk_inventory_import_items_batch FOREIGN KEY (batch_id) REFERENCES inventory_import_batches(id),
    CONSTRAINT fk_inventory_import_items_material FOREIGN KEY (material_id) REFERENCES materials(id),
    CONSTRAINT chk_inventory_import_items_row_number_positive CHECK (csv_row_number > 0),
    CONSTRAINT chk_inventory_import_items_validation CHECK (
        (valid = TRUE AND error_code IS NULL AND error_message IS NULL)
        OR (valid = FALSE AND error_code IS NOT NULL AND error_message IS NOT NULL)
    ),
    INDEX idx_inventory_import_items_batch_row (batch_id, csv_row_number),
    INDEX idx_inventory_import_items_material (material_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
