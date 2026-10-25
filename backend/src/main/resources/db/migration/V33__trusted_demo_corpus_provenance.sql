ALTER TABLE document_metadata
    MODIFY COLUMN uploaded_by BIGINT NULL,
    DROP CHECK chk_document_metadata_type,
    ADD COLUMN demo_dataset_key VARCHAR(40) NULL,
    ADD COLUMN demo_document_key VARCHAR(120) NULL,
    ADD COLUMN demo_theme_key VARCHAR(80) NULL,
    ADD COLUMN demo_corpus_version VARCHAR(50) NULL,
    ADD COLUMN demo_source_sha256 CHAR(64) NULL,
    ADD COLUMN demo_provenance_origin VARCHAR(30) NULL,
    ADD COLUMN semantic_review_actor_type VARCHAR(20) NULL,
    ADD COLUMN semantic_review_actor VARCHAR(120) NULL,
    ADD COLUMN semantic_review_trigger VARCHAR(120) NULL,
    ADD COLUMN semantic_review_policy_decision VARCHAR(30) NULL,
    ADD COLUMN semantic_review_invalidated_at DATETIME(6) NULL,
    ADD COLUMN semantic_review_invalidation_reason VARCHAR(80) NULL;

UPDATE document_metadata
   SET semantic_review_actor_type = 'USER',
       semantic_review_actor = CAST(semantic_approved_by AS CHAR),
       semantic_review_trigger = 'ADMIN_OUTBOUND_REVIEW',
       semantic_review_policy_decision = COALESCE(semantic_review_status, 'APPROVED'),
       semantic_review_version = COALESCE(semantic_review_version, version),
       semantic_review_status = COALESCE(semantic_review_status, 'APPROVED')
 WHERE semantic_approved_by IS NOT NULL;

ALTER TABLE document_metadata
    ADD CONSTRAINT chk_document_metadata_type CHECK (
        document_type COLLATE utf8mb4_bin IN (
            _utf8mb4'SUPPLIER_CONTRACT' COLLATE utf8mb4_bin,
            _utf8mb4'QUOTATION' COLLATE utf8mb4_bin,
            _utf8mb4'PROCUREMENT_SOP' COLLATE utf8mb4_bin,
            _utf8mb4'PRODUCT_SPECIFICATION' COLLATE utf8mb4_bin,
            _utf8mb4'QUALITY_REQUIREMENT' COLLATE utf8mb4_bin,
            _utf8mb4'SUPPLIER_PROFILE' COLLATE utf8mb4_bin,
            _utf8mb4'SUPPLIER_COOPERATION_RECORD' COLLATE utf8mb4_bin,
            _utf8mb4'SUPPLIER_EVALUATION' COLLATE utf8mb4_bin
        )
    ),
    ADD CONSTRAINT chk_document_demo_provenance CHECK (
        (demo_dataset_key IS NULL AND demo_document_key IS NULL AND demo_theme_key IS NULL
            AND demo_corpus_version IS NULL AND demo_source_sha256 IS NULL AND demo_provenance_origin IS NULL)
        OR
        (demo_dataset_key IS NOT NULL AND demo_document_key IS NOT NULL AND demo_theme_key IS NOT NULL
            AND demo_corpus_version IS NOT NULL AND demo_source_sha256 IS NOT NULL AND demo_provenance_origin IS NOT NULL
            AND demo_dataset_key COLLATE utf8mb4_bin = _utf8mb4'SP-901' COLLATE utf8mb4_bin
            AND demo_provenance_origin COLLATE utf8mb4_bin IN (
                _utf8mb4'LEGACY_SP906' COLLATE utf8mb4_bin,
                _utf8mb4'ADDED_SP907' COLLATE utf8mb4_bin
            ))
    ),
    ADD CONSTRAINT chk_document_semantic_review_actor_type CHECK (
        (semantic_review_actor_type IS NULL AND semantic_review_actor IS NULL
            AND semantic_review_trigger IS NULL AND semantic_review_policy_decision IS NULL
            AND semantic_approved_source_hash IS NULL AND semantic_approved_by IS NULL AND semantic_approved_at IS NULL
            AND semantic_review_version IS NULL AND semantic_review_status IS NULL AND semantic_review_reason IS NULL
            AND semantic_review_invalidated_at IS NULL AND semantic_review_invalidation_reason IS NULL)
        OR
        (semantic_review_actor_type IS NOT NULL AND semantic_review_actor_type COLLATE utf8mb4_bin = _utf8mb4'USER' COLLATE utf8mb4_bin
            AND semantic_review_actor IS NOT NULL AND semantic_review_trigger IS NOT NULL
            AND semantic_review_policy_decision IS NOT NULL AND semantic_review_status IS NOT NULL
            AND semantic_approved_source_hash IS NOT NULL AND semantic_approved_by IS NOT NULL AND semantic_approved_at IS NOT NULL
            AND semantic_review_version IS NOT NULL
            AND (semantic_review_status COLLATE utf8mb4_bin = _utf8mb4'APPROVED' COLLATE utf8mb4_bin OR semantic_review_reason IS NOT NULL)
            AND semantic_review_actor COLLATE utf8mb4_bin =
                CAST(semantic_approved_by AS CHAR CHARACTER SET utf8mb4) COLLATE utf8mb4_bin
            AND semantic_review_trigger COLLATE utf8mb4_bin = _utf8mb4'ADMIN_OUTBOUND_REVIEW' COLLATE utf8mb4_bin
            AND semantic_review_policy_decision COLLATE utf8mb4_bin = semantic_review_status COLLATE utf8mb4_bin)
        OR
        (semantic_review_actor_type IS NOT NULL AND semantic_review_actor_type COLLATE utf8mb4_bin = _utf8mb4'SYSTEM' COLLATE utf8mb4_bin
            AND semantic_review_actor IS NOT NULL AND semantic_review_trigger IS NOT NULL
            AND semantic_review_policy_decision IS NOT NULL AND semantic_review_status IS NOT NULL
            AND semantic_approved_source_hash IS NOT NULL AND semantic_approved_by IS NULL AND semantic_approved_at IS NOT NULL
            AND semantic_review_version IS NOT NULL
            AND (semantic_review_status COLLATE utf8mb4_bin = _utf8mb4'APPROVED' COLLATE utf8mb4_bin OR semantic_review_reason IS NOT NULL)
            AND semantic_review_actor COLLATE utf8mb4_bin = _utf8mb4'TRUSTED_DEMO_CORPUS' COLLATE utf8mb4_bin
            AND semantic_review_trigger COLLATE utf8mb4_bin = _utf8mb4'TRUSTED_DEMO_CORPUS_AUTO_REVIEW' COLLATE utf8mb4_bin
            AND semantic_review_policy_decision COLLATE utf8mb4_bin = semantic_review_status COLLATE utf8mb4_bin
            AND demo_dataset_key IS NOT NULL AND demo_document_key IS NOT NULL AND demo_theme_key IS NOT NULL
            AND demo_corpus_version IS NOT NULL AND demo_source_sha256 IS NOT NULL AND demo_provenance_origin IS NOT NULL
            AND demo_dataset_key COLLATE utf8mb4_bin = _utf8mb4'SP-901' COLLATE utf8mb4_bin)
    ),
    ADD CONSTRAINT chk_document_semantic_review_invalidation CHECK (
        (semantic_review_invalidated_at IS NULL AND semantic_review_invalidation_reason IS NULL)
        OR (semantic_review_invalidated_at IS NOT NULL AND semantic_review_invalidation_reason IS NOT NULL)
    ),
    ADD CONSTRAINT uq_document_demo_key_version UNIQUE (demo_dataset_key, demo_document_key, version),
    ADD INDEX idx_document_demo_baseline (demo_dataset_key, demo_corpus_version, demo_document_key);

CREATE TABLE demo_corpus_sync_state (
    dataset_key VARCHAR(40) NOT NULL,
    corpus_version VARCHAR(50) NOT NULL,
    status VARCHAR(30) NOT NULL,
    expected_documents INT NOT NULL DEFAULT 0,
    created_documents INT NOT NULL DEFAULT 0,
    adopted_legacy_documents INT NOT NULL DEFAULT 0,
    unchanged_documents INT NOT NULL DEFAULT 0,
    ready_documents INT NOT NULL DEFAULT 0,
    approved_documents INT NOT NULL DEFAULT 0,
    requires_review_documents INT NOT NULL DEFAULT 0,
    indexing_queued_documents INT NOT NULL DEFAULT 0,
    conflict_count INT NOT NULL DEFAULT 0,
    failure_reason VARCHAR(120) NULL,
    failed_documents INT NOT NULL DEFAULT 0,
    policy_blocked_documents INT NOT NULL DEFAULT 0,
    pending_index_documents INT NOT NULL DEFAULT 0,
    started_at DATETIME(6) NOT NULL,
    completed_at DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (dataset_key, corpus_version),
    CONSTRAINT chk_demo_corpus_sync_state_status CHECK (status IN (
        'RUNNING', 'COMPLETE', 'COMPLETE_WITH_CONFLICTS', 'FAILED'
    ))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE demo_corpus_sync_outcomes (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id CHAR(36) NOT NULL,
    dataset_key VARCHAR(40) NOT NULL,
    corpus_version VARCHAR(50) NOT NULL,
    document_key VARCHAR(120) NOT NULL,
    document_id BIGINT NULL,
    outcome VARCHAR(40) NOT NULL,
    ingestion_state VARCHAR(20) NULL,
    review_state VARCHAR(30) NULL,
    index_state VARCHAR(30) NULL,
    detail VARCHAR(120) NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uq_demo_corpus_sync_outcome UNIQUE (run_id, document_key),
    CONSTRAINT fk_demo_corpus_sync_outcome_document FOREIGN KEY (document_id)
        REFERENCES document_metadata(id),
    INDEX idx_demo_corpus_sync_outcome_run (dataset_key, corpus_version, run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE demo_corpus_sync_conflicts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    conflict_key CHAR(64) NOT NULL,
    dataset_key VARCHAR(40) NOT NULL,
    corpus_version VARCHAR(50) NOT NULL,
    document_key VARCHAR(120) NOT NULL,
    document_id BIGINT NULL,
    conflict_code VARCHAR(80) NOT NULL,
    details VARCHAR(500) NOT NULL,
    first_seen_at DATETIME(6) NOT NULL,
    last_seen_at DATETIME(6) NOT NULL,
    resolved_at DATETIME(6) NULL,
    CONSTRAINT uq_demo_corpus_sync_conflict_key UNIQUE (conflict_key),
    CONSTRAINT fk_demo_corpus_sync_conflict_document FOREIGN KEY (document_id)
        REFERENCES document_metadata(id),
    INDEX idx_demo_corpus_sync_conflict_current (dataset_key, corpus_version, resolved_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
