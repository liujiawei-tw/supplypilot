CREATE TABLE materials (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(50) NOT NULL,
    name VARCHAR(100) NOT NULL,
    base_unit VARCHAR(10) NOT NULL,
    safety_stock DECIMAL(19,4) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_materials_code UNIQUE (code),
    CONSTRAINT chk_materials_safety_stock_nonnegative CHECK (safety_stock >= 0),
    INDEX idx_materials_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE inventory (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    material_id BIGINT NOT NULL,
    quantity DECIMAL(19,4) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    version BIGINT NOT NULL,
    CONSTRAINT uk_inventory_material UNIQUE (material_id),
    CONSTRAINT fk_inventory_material FOREIGN KEY (material_id) REFERENCES materials(id),
    CONSTRAINT chk_inventory_quantity_nonnegative CHECK (quantity >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
