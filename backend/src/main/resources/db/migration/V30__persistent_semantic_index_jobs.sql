ALTER TABLE document_metadata
    ADD COLUMN semantic_index_generation BIGINT NOT NULL DEFAULT 0;

CREATE TABLE semantic_index_jobs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id BIGINT NOT NULL,
    document_version VARCHAR(50) NOT NULL,
    source_hash CHAR(64) NOT NULL,
    generation_key VARCHAR(150) NOT NULL,
    document_generation BIGINT NOT NULL,
    status VARCHAR(30) NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    retry_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NULL,
    lease_until DATETIME(6) NULL,
    failure_code VARCHAR(80) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_semantic_index_jobs_document FOREIGN KEY (document_id)
        REFERENCES document_metadata(id) ON DELETE CASCADE,
    CONSTRAINT uq_semantic_index_jobs_document_generation
        UNIQUE (document_id, document_version, source_hash, generation_key, document_generation),
    CONSTRAINT chk_semantic_index_jobs_status CHECK (status IN (
        'QUEUED', 'PROCESSING', 'RETRY_WAIT', 'SUCCEEDED', 'FAILED', 'SUPERSEDED'
    )),
    INDEX idx_semantic_index_jobs_poll (status, next_attempt_at, lease_until, id),
    INDEX idx_semantic_index_jobs_document (document_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
