CREATE TABLE document_metadata (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_type VARCHAR(40) NOT NULL,
    supplier_id BIGINT NULL,
    title VARCHAR(255) NOT NULL,
    version VARCHAR(50) NOT NULL,
    storage_path VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL,
    uploaded_by BIGINT NOT NULL,
    uploaded_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_document_metadata_supplier FOREIGN KEY (supplier_id) REFERENCES suppliers(id),
    CONSTRAINT fk_document_metadata_uploaded_by FOREIGN KEY (uploaded_by) REFERENCES users(id),
    CONSTRAINT chk_document_metadata_type CHECK (document_type IN (
        'SUPPLIER_CONTRACT', 'QUOTATION', 'PROCUREMENT_SOP', 'PRODUCT_SPECIFICATION', 'QUALITY_REQUIREMENT'
    )),
    CONSTRAINT chk_document_metadata_status CHECK (status IN ('PROCESSING', 'READY', 'FAILED', 'INACTIVE')),
    INDEX idx_document_metadata_type (document_type),
    INDEX idx_document_metadata_supplier (supplier_id),
    INDEX idx_document_metadata_status (status),
    INDEX idx_document_metadata_uploaded_at (uploaded_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
