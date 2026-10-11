package com.supplypilot.document.semantic;

import java.util.List;
import java.util.Properties;

import javax.sql.DataSource;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import org.flywaydb.core.Flyway;
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
                SELECT status, source_hash, expected_chunk_count, failure_reason, attempts
                  FROM semantic_document_indexes
                 WHERE document_id = ? AND document_version = ? AND generation_key = ?
                """, (rs, row) -> new IndexState(rs.getString(1), rs.getString(2), rs.getInt(3),
                        rs.getString(4), rs.getInt(5)), documentId, version, properties.generationKey());
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public void mark(long documentId, String version, String status, String reason,
                     String sourceHash, int expectedCount) {
        ensureGeneration();
        jdbc.update("""
                INSERT INTO semantic_document_indexes
                    (document_id, document_version, generation_key, status, source_hash,
                     expected_chunk_count, failure_reason, attempts)
                VALUES (?, ?, ?, ?, ?, ?, ?, 1)
                ON CONFLICT (document_id, document_version, generation_key)
                DO UPDATE SET status = EXCLUDED.status, source_hash = EXCLUDED.source_hash,
                  expected_chunk_count = EXCLUDED.expected_chunk_count,
                  failure_reason = EXCLUDED.failure_reason,
                  attempts = semantic_document_indexes.attempts + 1, updated_at = now()
                """, documentId, version, properties.generationKey(), status, sourceHash,
                expectedCount, reason);
    }

    public void replace(long documentId, String version, String sourceHash, List<SafeChunk> chunks) {
        ensureGeneration();
        transactions.executeWithoutResult(tx -> {
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
                      source_hash = ?, expected_chunk_count = ?, updated_at = now()
                     WHERE document_id = ? AND document_version = ? AND generation_key = ?
                    """, sourceHash, chunks.size(), documentId, version, properties.generationKey());
        });
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
                failed == 0 && review == 0 && pending == 0,
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

    public record IndexState(String status, String sourceHash, int expectedCount,
                             String failureReason, int attempts) { }
    public record SafeChunk(int index, int startOffset, int endOffset, String content,
                            List<Float> embedding) { }
    public record SourceChunk(int index, int startOffset, int endOffset, String content) { }
    public record Candidate(long documentId, String version, int chunkIndex, int startOffset,
                            int endOffset, String content, double score) { }
}
