ALTER TABLE semantic_document_indexes
    ADD COLUMN publication_generation BIGINT NOT NULL DEFAULT 0;
