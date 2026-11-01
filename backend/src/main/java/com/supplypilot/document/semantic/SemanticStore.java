package com.supplypilot.document.semantic;

import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.sql.DataSource;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import org.flywaydb.core.Flyway;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** PostgreSQL owns only semantic indexes. It never participates in MySQL transactions. */
@Component
public class SemanticStore {
    public static final String RAG_VECTOR_TABLE = "semantic_rag_vectors";

    private final SemanticProperties properties;
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private volatile boolean migrated;

    public SemanticStore(SemanticProperties properties) {
        this.properties = properties;
        if (properties.jdbcUrl() == null || properties.jdbcUrl().isBlank()) {
            dataSource = null;
            jdbc = null;
            transactions = null;
        } else {
            DriverManagerDataSource source = new DriverManagerDataSource();
            source.setDriverClassName("org.postgresql.Driver");
            source.setUrl(properties.jdbcUrl());
            source.setUsername(properties.username());
            source.setPassword(properties.password());
            Properties connectionTimeouts = new Properties();
            connectionTimeouts.setProperty("connectTimeout", "5");
            connectionTimeouts.setProperty("socketTimeout", "15");
            source.setConnectionProperties(connectionTimeouts);
            dataSource = source;
            jdbc = new JdbcTemplate(source);
            transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
        }
    }

    public synchronized void ensureSchema() {
        if (dataSource == null) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
        }
        if (!migrated) {
            Flyway.configure().dataSource(dataSource).locations("classpath:db/semantic")
                    .table("semantic_flyway_history")
                    .placeholders(Map.of("ragVectorDimensions", Integer.toString(properties.dimensions())))
                    .load().migrate();
            migrated = true;
        }
    }

    public void ensureGeneration() {
        ensureSchema();
        jdbc.update("""
                INSERT INTO semantic_generations(generation_key, model_id, dimensions, chunking_version)
                VALUES (?, ?, ?, ?) ON CONFLICT (generation_key) DO NOTHING
                """, properties.generationKey(), properties.modelId(), properties.dimensions(),
                SemanticProperties.CHUNKING_VERSION);
        List<Boolean> matches = jdbc.query("""
                SELECT model_id = ? AND dimensions = ? AND chunking_version = ?
                  FROM semantic_generations WHERE generation_key = ?
                """, (rs, row) -> rs.getBoolean(1), properties.modelId(), properties.dimensions(),
                SemanticProperties.CHUNKING_VERSION, properties.generationKey());
        if (matches.size() != 1 || !matches.getFirst()) {
            throw new IllegalStateException("Semantic generation conflicts with model, dimension or chunking version");
        }
    }

    public IndexState state(long documentId, String version) {
        ensureGeneration();
        List<IndexState> rows = jdbc.query("""
                SELECT status, source_hash, expected_chunk_count, failure_reason, attempts,
                       publication_generation
                  FROM semantic_document_indexes
                 WHERE document_id = ? AND document_version = ? AND generation_key = ?
                """, (rs, row) -> new IndexState(rs.getString(1), rs.getString(2), rs.getInt(3),
                        rs.getString(4), rs.getInt(5), rs.getLong(6)),
                documentId, version, properties.generationKey());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** Read-only publication check for the Documents API; it never runs Flyway or creates tables. */
    public PublicationCheck inspectPublication(long documentId, String version, String generationKey,
                                               String sourceHash, long documentGeneration,
                                               List<SourceChunk> expectedSource) {
        if (jdbc == null) throw new DataAccessResourceFailureException("Semantic database is not configured");
        List<IndexState> states = jdbc.query("""
                SELECT status, source_hash, expected_chunk_count, failure_reason, attempts,
                       publication_generation
                  FROM semantic_document_indexes
                 WHERE document_id = ? AND document_version = ? AND generation_key = ?
                """, (rs, row) -> new IndexState(rs.getString(1), rs.getString(2), rs.getInt(3),
                rs.getString(4), rs.getInt(5), rs.getLong(6)), documentId, version, generationKey);
        if (states.isEmpty()) return new PublicationCheck(null, false, 0);
        IndexState state = states.getFirst();
        List<SourceChunk> actual = readVectorSources(documentId, version, generationKey,
                sourceHash, documentGeneration);
        Integer count = vectorChunkCount(documentId, version, generationKey,
                sourceHash, documentGeneration);
        boolean current = "READY".equals(state.status())
                && sourceHash.equals(state.sourceHash())
                && state.expectedCount() == expectedSource.size()
                && state.publicationGeneration() == documentGeneration
                && actual.equals(expectedSource)
                && count != null && count == expectedSource.size();
        return new PublicationCheck(state, current, count == null ? 0 : Math.max(0, count));
    }

    public void mark(long documentId, String version, String status, String reason,
                     String sourceHash, int expectedCount) {
        mark(documentId, version, status, reason, sourceHash, expectedCount, 0L);
    }

    public void mark(long documentId, String version, String status, String reason,
                     String sourceHash, int expectedCount, long documentGeneration) {
        ensureGeneration();
        jdbc.update("""
                INSERT INTO semantic_document_indexes
                    (document_id, document_version, generation_key, status, source_hash,
                     expected_chunk_count, failure_reason, attempts, publication_generation)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?)
                ON CONFLICT (document_id, document_version, generation_key)
                DO UPDATE SET status = EXCLUDED.status, source_hash = EXCLUDED.source_hash,
                  expected_chunk_count = EXCLUDED.expected_chunk_count,
                  failure_reason = EXCLUDED.failure_reason,
                  publication_generation = EXCLUDED.publication_generation,
                  attempts = semantic_document_indexes.attempts + 1, updated_at = now()
                WHERE semantic_document_indexes.publication_generation < EXCLUDED.publication_generation
                   OR (semantic_document_indexes.publication_generation = EXCLUDED.publication_generation
                       AND semantic_document_indexes.status <> 'READY')
                """, documentId, version, properties.generationKey(), status, sourceHash,
                expectedCount, reason, documentGeneration);
    }

    /** Publishes lifecycle metadata after PgVectorStore has written and verified every chunk. */
    public boolean publish(long documentId, String version, String sourceHash, long documentGeneration,
                           List<SafeChunk> chunks) {
        ensureGeneration();
        List<SourceChunk> expected = chunks.stream().map(chunk -> new SourceChunk(chunk.index(),
                chunk.startOffset(), chunk.endOffset(), chunk.content())).toList();
        if (!expected.equals(readVectorSources(documentId, version, properties.generationKey(),
                sourceHash, documentGeneration))
                || vectorChunkCount(documentId, version, properties.generationKey(),
                sourceHash, documentGeneration) != expected.size()) {
            return false;
        }
        Boolean published = transactions.execute(transaction -> {
            jdbc.update("""
                    INSERT INTO semantic_document_indexes
                        (document_id, document_version, generation_key, status, source_hash,
                         expected_chunk_count, failure_reason, attempts, publication_generation)
                    VALUES (?, ?, ?, 'PROCESSING', NULL, 0, NULL, 0, 0)
                    ON CONFLICT (document_id, document_version, generation_key) DO NOTHING
                    """, documentId, version, properties.generationKey());

            List<PublicationState> current = jdbc.query("""
                    SELECT publication_generation, status, source_hash
                      FROM semantic_document_indexes
                     WHERE document_id = ? AND document_version = ? AND generation_key = ?
                     FOR UPDATE
                    """, (rs, row) -> new PublicationState(rs.getLong(1), rs.getString(2), rs.getString(3)),
                    documentId, version, properties.generationKey());
            if (current.isEmpty()) return false;
            PublicationState publication = current.getFirst();
            if (publication.generation() > documentGeneration
                    || (publication.generation() == documentGeneration
                    && "READY".equals(publication.status())
                    && !sourceHash.equals(publication.sourceHash()))) {
                return false;
            }

            jdbc.update("""
                    UPDATE semantic_document_indexes SET status = 'READY', failure_reason = NULL,
                      source_hash = ?, expected_chunk_count = ?, publication_generation = ?,
                      updated_at = now()
                     WHERE document_id = ? AND document_version = ? AND generation_key = ?
                    """, sourceHash, chunks.size(), documentGeneration, documentId, version,
                    properties.generationKey());

            // Agentic SQL still reads semantic_chunks as a derived PostgreSQL content projection.
            // Keep that contract current while all document-RAG retrieval uses PgVectorStore.
            jdbc.update("""
                    DELETE FROM semantic_chunks
                     WHERE document_id = ? AND document_version = ? AND generation_key = ?
                    """, documentId, version, properties.generationKey());
            int projected = jdbc.update("""
                    INSERT INTO semantic_chunks
                        (document_id, document_version, generation_key, chunk_index,
                         start_offset, end_offset, content, embedding)
                    SELECT (metadata::jsonb ->> 'documentId')::bigint,
                           metadata::jsonb ->> 'documentVersion',
                           metadata::jsonb ->> 'generationKey',
                           (metadata::jsonb ->> 'chunkIndex')::integer,
                           (metadata::jsonb ->> 'startOffset')::integer,
                           (metadata::jsonb ->> 'endOffset')::integer,
                           content, embedding
                      FROM semantic_rag_vectors
                     WHERE metadata::jsonb ->> 'documentId' = ?
                       AND metadata::jsonb ->> 'documentVersion' = ?
                       AND metadata::jsonb ->> 'generationKey' = ?
                       AND metadata::jsonb ->> 'sourceHash' = ?
                       AND metadata::jsonb ->> 'documentGeneration' = ?
                     ORDER BY (metadata::jsonb ->> 'chunkIndex')::integer
                    """, Long.toString(documentId), version, properties.generationKey(), sourceHash,
                    Long.toString(documentGeneration));
            if (projected != chunks.size()) {
                throw new IllegalStateException("Agentic SQL semantic projection is incomplete");
            }
            return true;
        });
        return Boolean.TRUE.equals(published);
    }


    public int chunkCount(long documentId, String version) {
        ensureGeneration();
        IndexState publication = state(documentId, version);
        if (publication == null || publication.sourceHash() == null) return 0;
        return vectorChunkCount(documentId, version, properties.generationKey(),
                publication.sourceHash(), publication.publicationGeneration());
    }

    public boolean matchesSource(long documentId, String version, List<SourceChunk> expected) {
        ensureGeneration();
        IndexState publication = state(documentId, version);
        if (publication == null || publication.sourceHash() == null) return false;
        return matchesSource(documentId, version, publication.sourceHash(),
                publication.publicationGeneration(), expected);
    }

    public boolean matchesSource(long documentId, String version, String sourceHash,
                                 long documentGeneration, List<SourceChunk> expected) {
        ensureGeneration();
        return expected.equals(readVectorSources(documentId, version, properties.generationKey(),
                sourceHash, documentGeneration))
                && vectorChunkCount(documentId, version, properties.generationKey(), sourceHash,
                documentGeneration) == expected.size();
    }

    public long audit(long actorUserId, int ready, int skipped, int failed, int review, int pending) {
        ensureGeneration();
        return jdbc.queryForObject("""
                INSERT INTO semantic_gate_audit(generation_key, actor_user_id, passed,
                    ready_count, skipped_count, failed_count, review_count, pending_count)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """, Long.class, properties.generationKey(), actorUserId,
                ready > 0 && failed == 0 && review == 0 && pending == 0,
                ready, skipped, failed, review, pending);
    }

    public boolean hasPassingAudit() {
        ensureGeneration();
        List<Boolean> latest = jdbc.query("""
                SELECT passed FROM semantic_gate_audit WHERE generation_key = ?
                 ORDER BY id DESC LIMIT 1
                """, (rs, row) -> rs.getBoolean(1), properties.generationKey());
        return !latest.isEmpty() && latest.getFirst();
    }

    /** Clears the dedicated demo semantic store. Callers must enforce the demo-only environment guard. */
    public void resetAllDemoData(String expectedDatabase) {
        String actualDatabase = jdbc.queryForObject("SELECT current_database()", String.class);
        if (!expectedDatabase.equals(actualDatabase)) {
            throw new IllegalStateException("Refusing semantic reset for unexpected PostgreSQL database");
        }
        ensureSchema();
        transactions.executeWithoutResult(transaction -> {
            jdbc.update("DELETE FROM semantic_gate_audit");
            jdbc.update("DELETE FROM " + RAG_VECTOR_TABLE);
            jdbc.update("DELETE FROM semantic_chunks");
            jdbc.update("DELETE FROM semantic_document_indexes");
            jdbc.update("DELETE FROM semantic_generations");
        });
    }

    private List<SourceChunk> readVectorSources(long documentId, String version, String generationKey,
                                                String sourceHash, long documentGeneration) {
        return jdbc.query("""
                SELECT (metadata::jsonb ->> 'chunkIndex')::integer,
                       (metadata::jsonb ->> 'startOffset')::integer,
                       (metadata::jsonb ->> 'endOffset')::integer, content
                  FROM semantic_rag_vectors
                 WHERE metadata::jsonb ->> 'documentId' = ?
                   AND metadata::jsonb ->> 'documentVersion' = ?
                   AND metadata::jsonb ->> 'generationKey' = ?
                   AND metadata::jsonb ->> 'sourceHash' = ?
                   AND metadata::jsonb ->> 'documentGeneration' = ?
                 ORDER BY (metadata::jsonb ->> 'chunkIndex')::integer
                """, (rs, row) -> new SourceChunk(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getString(4)),
                Long.toString(documentId), version, generationKey, sourceHash,
                Long.toString(documentGeneration));
    }

    private int vectorChunkCount(long documentId, String version, String generationKey,
                                 String sourceHash, long documentGeneration) {
        Integer count = jdbc.queryForObject("""
                SELECT CASE WHEN count(*) = count(*) FILTER (WHERE vector_dims(embedding) = ?)
                            THEN count(*) ELSE -1 END
                  FROM semantic_rag_vectors
                 WHERE metadata::jsonb ->> 'documentId' = ?
                   AND metadata::jsonb ->> 'documentVersion' = ?
                   AND metadata::jsonb ->> 'generationKey' = ?
                   AND metadata::jsonb ->> 'sourceHash' = ?
                   AND metadata::jsonb ->> 'documentGeneration' = ?
                """, Integer.class, properties.dimensions(), Long.toString(documentId), version,
                generationKey, sourceHash, Long.toString(documentGeneration));
        return count == null ? 0 : count;
    }

    /** Returns distinct document IDs in the Spring AI store for bounded stale-vector reconciliation. */
    public List<Long> vectorDocumentIdsAfter(long afterDocumentId, int limit) {
        if (limit < 1 || limit > 1_000) throw new IllegalArgumentException("Invalid vector cleanup page size");
        if (jdbc == null) return List.of();
        return jdbc.query("""
                SELECT DISTINCT (metadata::jsonb ->> 'documentId')::bigint AS document_id
                  FROM semantic_rag_vectors
                 WHERE metadata::jsonb ? 'documentId'
                   AND (metadata::jsonb ->> 'documentId')::bigint > ?
                 ORDER BY document_id
                 LIMIT ?
                """, (rs, row) -> rs.getLong(1), afterDocumentId, limit);
    }

    /** Returns every Spring AI vector ID belonging to a document, including obsolete versions. */
    public List<String> vectorIdsForDocument(long documentId) {
        if (jdbc == null) return List.of();
        return jdbc.query("""
                SELECT id::text FROM semantic_rag_vectors
                 WHERE metadata::jsonb ->> 'documentId' = ?
                 ORDER BY id
                """, (rs, row) -> rs.getString(1), Long.toString(documentId));
    }

    public List<VectorReference> vectorReferencesForDocument(long documentId) {
        if (jdbc == null) return List.of();
        return jdbc.query("""
                SELECT id::text,
                       metadata::jsonb ->> 'documentVersion',
                       metadata::jsonb ->> 'generationKey',
                       metadata::jsonb ->> 'sourceHash',
                       metadata::jsonb ->> 'documentGeneration'
                  FROM semantic_rag_vectors
                 WHERE metadata::jsonb ->> 'documentId' = ?
                 ORDER BY id
                """, (rs, row) -> new VectorReference(rs.getString(1), rs.getString(2),
                rs.getString(3), rs.getString(4), rs.getString(5)), Long.toString(documentId));
    }

    private record PublicationState(long generation, String status, String sourceHash) { }
    public record IndexState(String status, String sourceHash, int expectedCount,
                             String failureReason, int attempts, long publicationGeneration) {
        public IndexState(String status, String sourceHash, int expectedCount,
                          String failureReason, int attempts) {
            this(status, sourceHash, expectedCount, failureReason, attempts, 0);
        }
    }
    public record PublicationCheck(IndexState state, boolean current, int publishedChunks) { }
    public record SafeChunk(int index, int startOffset, int endOffset, String content) { }
    public record SourceChunk(int index, int startOffset, int endOffset, String content) { }
    public record VectorReference(String id, String documentVersion, String generationKey,
                                  String sourceHash, String documentGeneration) { }
}
