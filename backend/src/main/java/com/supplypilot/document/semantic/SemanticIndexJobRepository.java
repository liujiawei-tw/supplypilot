package com.supplypilot.document.semantic;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class SemanticIndexJobRepository {
    private static final RowMapper<SemanticIndexJob> JOB_MAPPER = SemanticIndexJobRepository::mapJob;

    private final JdbcTemplate jdbc;

    public SemanticIndexJobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void enqueue(long documentId, String version, String sourceHash, String generationKey,
                        long documentGeneration, Instant now) {
        jdbc.update("""
                INSERT INTO semantic_index_jobs
                    (document_id, document_version, source_hash, generation_key, document_generation,
                     status, attempts, retry_count, next_attempt_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'QUEUED', 0, 0, ?, ?, ?)
                ON DUPLICATE KEY UPDATE id = id
                """, documentId, version, sourceHash, generationKey, documentGeneration,
                timestamp(now), timestamp(now), timestamp(now));
    }

    @Transactional(readOnly = true)
    public List<SemanticIndexJob> latest(int limit) {
        if (limit < 1 || limit > 200) throw new IllegalArgumentException("Invalid job limit");
        return jdbc.query("""
                SELECT id, document_id, document_version, source_hash, generation_key,
                       document_generation, status, attempts, retry_count, next_attempt_at,
                       lease_until, failure_code, created_at, updated_at
                  FROM semantic_index_jobs ORDER BY id DESC LIMIT ?
                """, JOB_MAPPER, limit);
    }

    @Transactional(readOnly = true)
    public Optional<SemanticIndexJob> findById(long id) {
        return one("""
                SELECT id, document_id, document_version, source_hash, generation_key,
                       document_generation, status, attempts, retry_count, next_attempt_at,
                       lease_until, failure_code, created_at, updated_at
                  FROM semantic_index_jobs WHERE id = ?
                """, id);
    }

    @Transactional(readOnly = true)
    public Optional<SemanticIndexJob> findCurrent(long documentId, String version, String sourceHash,
                                                  String generationKey, long documentGeneration) {
        List<SemanticIndexJob> jobs = jdbc.query("""
                SELECT id, document_id, document_version, source_hash, generation_key,
                       document_generation, status, attempts, retry_count, next_attempt_at,
                       lease_until, failure_code, created_at, updated_at
                  FROM semantic_index_jobs
                 WHERE document_id = ? AND document_version = ? AND source_hash = ?
                   AND generation_key = ? AND document_generation = ?
                 ORDER BY id DESC LIMIT 1
                """, JOB_MAPPER, documentId, version, sourceHash, generationKey, documentGeneration);
        return jobs.stream().findFirst();
    }

    @Transactional
    public Optional<SemanticIndexJob> findByIdForUpdate(long id) {
        return one("""
                SELECT id, document_id, document_version, source_hash, generation_key,
                       document_generation, status, attempts, retry_count, next_attempt_at,
                       lease_until, failure_code, created_at, updated_at
                  FROM semantic_index_jobs WHERE id = ? FOR UPDATE
                """, id);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<SemanticIndexJob> claimNext(Instant now, Instant leaseUntil, int maxAttempts) {
        Timestamp nowValue = timestamp(now);
        jdbc.update("""
                UPDATE semantic_index_jobs
                   SET status = 'FAILED', lease_until = NULL, next_attempt_at = NULL,
                       failure_code = 'JOB_LEASE_EXPIRED', updated_at = ?
                 WHERE status = 'PROCESSING' AND lease_until <= ? AND attempts >= ?
                """, nowValue, nowValue, maxAttempts);
        List<Long> ids = jdbc.query("""
                SELECT id FROM semantic_index_jobs
                 WHERE attempts < ? AND (
                       (status IN ('QUEUED', 'RETRY_WAIT') AND next_attempt_at <= ?)
                    OR (status = 'PROCESSING' AND lease_until <= ?))
                 ORDER BY COALESCE(next_attempt_at, created_at), id
                 LIMIT 1 FOR UPDATE SKIP LOCKED
                """, (rs, row) -> rs.getLong(1), maxAttempts, nowValue, nowValue);
        if (ids.isEmpty()) return Optional.empty();
        long id = ids.getFirst();
        int updated = jdbc.update("""
                UPDATE semantic_index_jobs
                   SET status = 'PROCESSING', attempts = attempts + 1,
                       next_attempt_at = NULL, lease_until = ?, updated_at = ?
                 WHERE id = ? AND attempts < ?
                """, timestamp(leaseUntil), nowValue, id, maxAttempts);
        return updated == 1 ? findById(id) : Optional.empty();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean renewLease(long id, int attempt, Instant leaseUntil, Instant now) {
        return jdbc.update("""
                UPDATE semantic_index_jobs SET lease_until = ?, updated_at = ?
                 WHERE id = ? AND status = 'PROCESSING' AND attempts = ?
                """, timestamp(leaseUntil), timestamp(now), id, attempt) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean succeed(long id, int attempt, Instant now) {
        return jdbc.update("""
                UPDATE semantic_index_jobs
                   SET status = 'SUCCEEDED', failure_code = NULL, lease_until = NULL,
                       next_attempt_at = NULL, updated_at = ?
                 WHERE id = ? AND status = 'PROCESSING' AND attempts = ?
                """, timestamp(now), id, attempt) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean supersede(long id, int attempt, String safeCode, Instant now) {
        return jdbc.update("""
                UPDATE semantic_index_jobs
                   SET status = 'SUPERSEDED', failure_code = ?, lease_until = NULL,
                       next_attempt_at = NULL, updated_at = ?
                 WHERE id = ? AND status = 'PROCESSING' AND attempts = ?
                """, safeCode, timestamp(now), id, attempt) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean fail(long id, int attempt, String safeCode, boolean retryable, Instant nextAttemptAt,
                        int maxAttempts, Instant now) {
        Timestamp next = retryable && attempt < maxAttempts ? timestamp(nextAttemptAt) : null;
        return jdbc.update("""
                UPDATE semantic_index_jobs
                   SET status = CASE WHEN ? AND attempts < ? THEN 'RETRY_WAIT' ELSE 'FAILED' END,
                       failure_code = ?, lease_until = NULL, next_attempt_at = ?, updated_at = ?
                 WHERE id = ? AND status = 'PROCESSING' AND attempts = ?
                """, retryable, maxAttempts, safeCode, next, timestamp(now), id, attempt) == 1;
    }

    @Transactional
    public boolean queueManualRetry(long id, Instant now) {
        return jdbc.update("""
                UPDATE semantic_index_jobs
                   SET status = 'QUEUED', attempts = 0, retry_count = retry_count + 1,
                       next_attempt_at = ?, lease_until = NULL, failure_code = NULL, updated_at = ?
                 WHERE id = ? AND status = 'FAILED'
                """, timestamp(now), timestamp(now), id) == 1;
    }

    private Optional<SemanticIndexJob> one(String sql, long id) {
        List<SemanticIndexJob> jobs = jdbc.query(sql, JOB_MAPPER, id);
        return jobs.stream().findFirst();
    }

    private static SemanticIndexJob mapJob(ResultSet rs, int row) throws SQLException {
        return new SemanticIndexJob(rs.getLong("id"), rs.getLong("document_id"),
                rs.getString("document_version"), rs.getString("source_hash"),
                rs.getString("generation_key"), rs.getLong("document_generation"),
                SemanticIndexJobStatus.valueOf(rs.getString("status")), rs.getInt("attempts"),
                rs.getInt("retry_count"), instant(rs, "next_attempt_at"), instant(rs, "lease_until"),
                rs.getString("failure_code"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
