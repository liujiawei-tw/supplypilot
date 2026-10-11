CREATE TABLE document_chunks (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    document_id BIGINT NOT NULL,
    chunk_index INT NOT NULL,
    start_offset INT NOT NULL,
    end_offset INT NOT NULL,
    content TEXT NOT NULL,
    embedding TEXT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT fk_document_chunks_document FOREIGN KEY (document_id)
        REFERENCES document_metadata(id) ON DELETE CASCADE,
    CONSTRAINT uq_document_chunks_document_index UNIQUE (document_id, chunk_index),
    INDEX idx_document_chunks_document (document_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
