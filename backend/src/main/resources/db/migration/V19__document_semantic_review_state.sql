ALTER TABLE document_metadata
    ADD COLUMN semantic_review_version VARCHAR(50) NULL,
    ADD COLUMN semantic_review_status VARCHAR(30) NULL,
    ADD COLUMN semantic_review_reason VARCHAR(120) NULL;
