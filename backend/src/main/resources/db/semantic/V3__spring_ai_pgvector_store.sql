CREATE TABLE IF NOT EXISTS semantic_rag_vectors (
    id uuid PRIMARY KEY,
    content text NOT NULL,
    metadata json,
    embedding vector(${ragVectorDimensions}) NOT NULL
);

COMMENT ON TABLE semantic_rag_vectors IS
    'Spring AI PgVectorStore for the approved document RAG pipeline; legacy semantic tables are retained for Agentic SQL compatibility.';
