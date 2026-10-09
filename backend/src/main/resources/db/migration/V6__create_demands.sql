CREATE TABLE demands (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    material_id BIGINT NOT NULL,
    quantity DECIMAL(19,4) NOT NULL,
    required_date DATE NOT NULL,
    status VARCHAR(20) NOT NULL,
    note VARCHAR(500) NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_demands_material FOREIGN KEY (material_id) REFERENCES materials(id),
    CONSTRAINT fk_demands_created_by FOREIGN KEY (created_by) REFERENCES users(id),
    CONSTRAINT chk_demands_quantity_positive CHECK (quantity > 0),
    INDEX idx_demands_material_status_required_date (material_id, status, required_date),
    INDEX idx_demands_required_date (required_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
