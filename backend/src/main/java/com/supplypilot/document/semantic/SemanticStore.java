package com.supplypilot.document.semantic;

import java.util.List;
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
                    .table("semantic_flyway_history").load().migrate();
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
        List<SourceChunk> actual = jdbc.query("""
                SELECT chunk_index, start_offset, end_offset, content
                  FROM semantic_chunks
                 WHERE document_id = ? AND document_version = ? AND generation_key = ?
                 ORDER BY chunk_index
                """, (rs, row) -> new SourceChunk(rs.getInt(1), rs.getInt(2),
                rs.getInt(3), rs.getString(4)), documentId, version, generationKey);
        Integer count = jdbc.queryForObject("""
                SELECT CASE WHEN count(*) = count(*) FILTER (WHERE vector_dims(embedding) = ?)
                            THEN count(*) ELSE -1 END
                  FROM semantic_chunks
                 WHERE document_id = ? AND document_version = ? AND generation_key = ?
                """, Integer.class, properties.dimensions(), documentId, version, generationKey);
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

    /**
     * Publishes all chunks and the READY marker in one PostgreSQL transaction.
     * A delayed worker from an older approval generation cannot replace a newer publication.
     */
    public boolean publish(long documentId, String version, String sourceHash, long documentGeneration,
                           List<SafeChunk> chunks) {
        ensureGeneration();
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
                    UPDATE semantic_document_indexes
                       SET status = 'PROCESSING', source_hash = ?, expected_chunk_count = ?,
                           failure_reason = NULL, publication_generation = ?, updated_at = now()
                     WHERE document_id = ? AND document_version = ? AND generation_key = ?
                    """, sourceHash, chunks.size(), documentGeneration, documentId, version,
                    properties.generationKey());
            jdbc.update("""
                    DELETE FROM semantic_chunks WHERE document_id = ? AND document_version = ?
                      AND generation_key = ?
                    """, documentId, version, properties.generationKey());
            for (SafeChunk chunk : chunks) {
                jdbc.update("""
                        INSERT INTO semantic_chunks(document_id, document_version, generation_key,
                            chunk_index, start_offset, end_offset, content, embedding)
                        VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS vector))
                        """, documentId, version, properties.generationKey(), chunk.index(),
                        chunk.startOffset(), chunk.endOffset(), chunk.content(), vector(chunk.embedding()));
            }
            jdbc.update("""
                    UPDATE semantic_document_indexes SET status = 'READY', failure_reason = NULL,
                      source_hash = ?, expected_chunk_count = ?, publication_generation = ?,
                      updated_at = now()
                     WHERE document_id = ? AND document_version = ? AND generation_key = ?
                    """, sourceHash, chunks.size(), documentGeneration, documentId, version,
                    properties.generationKey());
            return true;
        });
        return Boolean.TRUE.equals(published);
    }


    public int chunkCount(long documentId, String version) {
        ensureGeneration();
        return jdbc.queryForObject("""
                SELECT CASE WHEN count(*) = count(*) FILTER (WHERE vector_dims(embedding) = ?)
                            THEN count(*) ELSE -1 END
                  FROM semantic_chunks WHERE document_id = ? AND document_version = ?
                    AND generation_key = ?
                """, Integer.class, properties.dimensions(), documentId, version,
                properties.generationKey());
    }

    public boolean matchesSource(long documentId, String version, List<SourceChunk> expected) {
        ensureGeneration();
        List<SourceChunk> actual = jdbc.query("""
                SELECT chunk_index, start_offset, end_offset, content
                  FROM semantic_chunks WHERE document_id = ? AND document_version = ?
                    AND generation_key = ? ORDER BY chunk_index
                """, (rs, row) -> new SourceChunk(rs.getInt(1), rs.getInt(2),
                        rs.getInt(3), rs.getString(4)), documentId, version,
                properties.generationKey());
        return actual.equals(expected) && chunkCount(documentId, version) == expected.size();
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
            jdbc.update("DELETE FROM semantic_chunks");
            jdbc.update("DELETE FROM semantic_document_indexes");
            jdbc.update("DELETE FROM semantic_generations");
        });
    }

    public List<Candidate> search(List<Float> query, int topK) {
        return search(query, topK, 0);
    }

    public List<Candidate> search(List<Float> query, int topK, int offset) {
        ensureGeneration();
        return jdbc.query("""
                SELECT c.document_id, c.document_version, c.chunk_index, c.start_offset,
                       c.end_offset, c.content, 1 - (c.embedding <=> CAST(? AS vector)) AS score
                  FROM semantic_chunks c JOIN semantic_document_indexes i
                    ON i.document_id = c.document_id AND i.document_version = c.document_version
                   AND i.generation_key = c.generation_key
                 WHERE c.generation_key = ? AND i.status = 'READY'
                 ORDER BY c.embedding <=> CAST(? AS vector), c.document_id, c.chunk_index
                 LIMIT ? OFFSET ?
                """, (rs, row) -> new Candidate(rs.getLong(1), rs.getString(2), rs.getInt(3),
                        rs.getInt(4), rs.getInt(5), rs.getString(6), rs.getDouble(7)),
                vector(query), properties.generationKey(), vector(query), topK, offset);
    }

    private String vector(List<Float> values) {
        if (values.size() != properties.dimensions()) {
            throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
        }
        StringBuilder result = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            float value = values.get(i);
            if (!Float.isFinite(value)) {
                throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
            }
            if (i > 0) result.append(',');
            result.append(value);
        }
        return result.append(']').toString();
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
    public record SafeChunk(int index, int startOffset, int endOffset, String content,
                            List<Float> embedding) { }
    public record SourceChunk(int index, int startOffset, int endOffset, String content) { }
    public record Candidate(long documentId, String version, int chunkIndex, int startOffset,
                            int endOffset, String content, double score) { }
}
