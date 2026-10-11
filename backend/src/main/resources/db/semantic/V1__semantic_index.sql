CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE semantic_generations (
    generation_key varchar(150) PRIMARY KEY,
    model_id varchar(120) NOT NULL,
    dimensions integer NOT NULL CHECK (dimensions BETWEEN 1 AND 3072),
    chunking_version varchar(40) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE semantic_document_indexes (
    document_id bigint NOT NULL,
    document_version varchar(50) NOT NULL,
    generation_key varchar(150) NOT NULL REFERENCES semantic_generations(generation_key),
    status varchar(30) NOT NULL,
    source_hash varchar(64),
    expected_chunk_count integer NOT NULL DEFAULT 0,
    failure_reason varchar(120),
    attempts integer NOT NULL DEFAULT 0,
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (document_id, document_version, generation_key)
);

CREATE TABLE semantic_chunks (
    document_id bigint NOT NULL,
    document_version varchar(50) NOT NULL,
    generation_key varchar(150) NOT NULL,
    chunk_index integer NOT NULL,
    start_offset integer NOT NULL,
    end_offset integer NOT NULL,
    content text NOT NULL,
    embedding vector NOT NULL,
    PRIMARY KEY (document_id, document_version, generation_key, chunk_index),
    FOREIGN KEY (document_id, document_version, generation_key)
      REFERENCES semantic_document_indexes(document_id, document_version, generation_key)
);

CREATE TABLE semantic_gate_audit (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    generation_key varchar(150) NOT NULL,
    actor_user_id bigint NOT NULL,
    passed boolean NOT NULL,
    ready_count integer NOT NULL,
    skipped_count integer NOT NULL,
    failed_count integer NOT NULL,
    review_count integer NOT NULL,
    pending_count integer NOT NULL,
    checked_at timestamptz NOT NULL DEFAULT now()
);

-- No ANN index in V1: pgvector's <=> operator scans all vectors for exact cosine distance.
