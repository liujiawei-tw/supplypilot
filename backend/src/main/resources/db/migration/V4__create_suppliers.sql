CREATE TABLE suppliers (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    code VARCHAR(50) NOT NULL,
    name VARCHAR(100) NOT NULL,
    country VARCHAR(100) NULL,
    reliability_score DECIMAL(5,2) NOT NULL,
    quality_score DECIMAL(5,2) NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_suppliers_code UNIQUE (code),
    CONSTRAINT chk_suppliers_reliability_score_range CHECK (reliability_score >= 0 AND reliability_score <= 100),
    CONSTRAINT chk_suppliers_quality_score_range CHECK (quality_score >= 0 AND quality_score <= 100),
    INDEX idx_suppliers_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
