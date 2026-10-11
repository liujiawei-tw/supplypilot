ALTER TABLE document_metadata
    ADD COLUMN semantic_approved_source_hash CHAR(64) NULL,
    ADD COLUMN semantic_approved_by BIGINT NULL,
    ADD COLUMN semantic_approved_at DATETIME(6) NULL;
