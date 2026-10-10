CREATE TABLE purchase_recommendation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    material_id BIGINT NOT NULL,
    material_code_snapshot VARCHAR(50) NOT NULL,
    material_name_snapshot VARCHAR(100) NOT NULL,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    expected_demand DECIMAL(19,4) NOT NULL,
    safety_stock DECIMAL(19,4) NOT NULL,
    current_inventory DECIMAL(19,4) NOT NULL,
    incoming_inventory DECIMAL(19,4) NOT NULL,
    net_requirement DECIMAL(19,4) NOT NULL,
    outcome VARCHAR(30) NOT NULL,
    recommended_supplier_id BIGINT NULL,
    recommended_quantity DECIMAL(19,4) NULL,
    recommended_supplier_score DECIMAL(17,14) NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_purchase_recommendation_material FOREIGN KEY (material_id) REFERENCES materials(id),
    CONSTRAINT fk_purchase_recommendation_supplier FOREIGN KEY (recommended_supplier_id) REFERENCES suppliers(id),
    CONSTRAINT fk_purchase_recommendation_created_by FOREIGN KEY (created_by) REFERENCES users(id),
    CONSTRAINT chk_purchase_recommendation_period CHECK (period_end >= period_start),
    CONSTRAINT chk_purchase_recommendation_snapshot_nonnegative CHECK (
        expected_demand >= 0 AND safety_stock >= 0 AND current_inventory >= 0
        AND incoming_inventory >= 0 AND net_requirement >= 0
    ),
    CONSTRAINT chk_purchase_recommendation_outcome CHECK (
        (outcome = 'PURCHASE_NOT_REQUIRED' AND net_requirement = 0
            AND recommended_supplier_id IS NULL AND recommended_quantity IS NULL AND recommended_supplier_score IS NULL)
        OR (outcome = 'SUPPLIER_RECOMMENDED' AND net_requirement > 0
            AND recommended_supplier_id IS NOT NULL AND recommended_quantity IS NOT NULL AND recommended_supplier_score IS NOT NULL)
        OR (outcome = 'NO_QUALIFIED_SUPPLIER' AND net_requirement > 0
            AND recommended_supplier_id IS NULL AND recommended_quantity IS NULL AND recommended_supplier_score IS NULL)
    ),
    INDEX idx_purchase_recommendation_material_created_at (material_id, created_at),
    INDEX idx_purchase_recommendation_period_end (period_end)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE supplier_recommendation_result (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    recommendation_id BIGINT NOT NULL,
    supplier_id BIGINT NOT NULL,
    supplier_code_snapshot VARCHAR(50) NOT NULL,
    supplier_name_snapshot VARCHAR(100) NOT NULL,
    qualified BOOLEAN NOT NULL,
    disqualified_reason VARCHAR(40) NULL,
    recommended_quantity DECIMAL(19,4) NULL,
    unit_price_snapshot DECIMAL(19,4) NULL,
    total_cost DECIMAL(19,4) NULL,
    lead_time_days_snapshot INT NULL,
    reliability_score_snapshot DECIMAL(5,2) NULL,
    quality_score_snapshot DECIMAL(5,2) NULL,
    price_score DECIMAL(15,12) NULL,
    lead_time_score DECIMAL(15,12) NULL,
    reliability_score DECIMAL(15,12) NULL,
    quality_score DECIMAL(15,12) NULL,
    total_score DECIMAL(17,14) NULL,
    `rank` INT NULL,
    CONSTRAINT fk_supplier_recommendation_result_recommendation FOREIGN KEY (recommendation_id) REFERENCES purchase_recommendation(id),
    CONSTRAINT fk_supplier_recommendation_result_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers(id),
    CONSTRAINT chk_supplier_recommendation_result_shape CHECK (
        (qualified = TRUE AND disqualified_reason IS NULL
            AND recommended_quantity IS NOT NULL AND unit_price_snapshot IS NOT NULL AND total_cost IS NOT NULL
            AND lead_time_days_snapshot IS NOT NULL AND reliability_score_snapshot IS NOT NULL AND quality_score_snapshot IS NOT NULL
            AND price_score IS NOT NULL AND lead_time_score IS NOT NULL AND reliability_score IS NOT NULL
            AND quality_score IS NOT NULL AND total_score IS NOT NULL AND `rank` IS NOT NULL)
        OR (qualified = FALSE AND disqualified_reason IS NOT NULL
            AND recommended_quantity IS NULL AND unit_price_snapshot IS NULL AND total_cost IS NULL
            AND lead_time_days_snapshot IS NULL AND reliability_score_snapshot IS NULL AND quality_score_snapshot IS NULL
            AND price_score IS NULL AND lead_time_score IS NULL AND reliability_score IS NULL
            AND quality_score IS NULL AND total_score IS NULL AND `rank` IS NULL)
    ),
    CONSTRAINT chk_supplier_recommendation_result_values CHECK (
        (recommended_quantity IS NULL OR recommended_quantity > 0)
        AND (unit_price_snapshot IS NULL OR unit_price_snapshot >= 0)
        AND (total_cost IS NULL OR total_cost >= 0)
        AND (lead_time_days_snapshot IS NULL OR lead_time_days_snapshot >= 0)
        AND (`rank` IS NULL OR `rank` > 0)
    ),
    INDEX idx_supplier_recommendation_result_recommendation (recommendation_id),
    INDEX idx_supplier_recommendation_result_recommendation_rank (recommendation_id, `rank`),
    INDEX idx_supplier_recommendation_result_supplier (supplier_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
