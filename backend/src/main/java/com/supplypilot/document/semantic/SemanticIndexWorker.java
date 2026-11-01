package com.supplypilot.document.semantic;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** Executes one durable indexing job. No MySQL transaction spans provider calls. */
@Service
public class SemanticIndexWorker {
    private static final Logger logger = LoggerFactory.getLogger(SemanticIndexWorker.class);
    private static final Instant MAX_MYSQL_DATETIME = LocalDateTime
            .of(9999, 12, 31, 23, 59, 59, 999_999_000).toInstant(ZoneOffset.UTC);

    private final SemanticIndexJobRepository jobs;
    private final SemanticIndexSnapshotReader snapshots;
    private final SpringAiVectorIndexService vectorIndex;
    private final SemanticStore store;
    private final SemanticIndexingProperties indexingProperties;
    private final Clock clock;

    public SemanticIndexWorker(SemanticIndexJobRepository jobs, SemanticIndexSnapshotReader snapshots,
                               SpringAiVectorIndexService vectorIndex, SemanticStore store,
                               SemanticIndexingProperties indexingProperties,
                               Clock clock) {
        this.jobs = jobs;
        this.snapshots = snapshots;
        this.vectorIndex = vectorIndex;
        this.store = store;
        this.indexingProperties = indexingProperties;
        this.clock = clock;
    }

    /** @return true when a job was claimed and handled. */
    public boolean processNext() {
        Instant now = clock.instant();
        SemanticIndexJob job = jobs.claimNext(now, now.plus(indexingProperties.leaseDuration()),
                indexingProperties.maxAttempts()).orElse(null);
        if (job == null) return false;
        SemanticIndexSnapshotReader.Snapshot indexedSnapshot = null;
        try {
            store.ensureGeneration();
            SemanticIndexSnapshotReader.Result result = snapshots.load(job);
            if (result.snapshot() == null) {
                jobs.supersede(job.id(), job.attempts(), result.rejectionCode(), clock.instant());
                return true;
            }
            SemanticIndexSnapshotReader.Snapshot snapshot = result.snapshot();
            indexedSnapshot = snapshot;
            boolean published = vectorIndex.index(job.documentId(), snapshot.version(), snapshot.sourceHash(),
                    job.documentGeneration(), snapshot.chunks(), () -> snapshots.isCurrentApproved(job)
                            && jobs.renewLease(job.id(), job.attempts(),
                            clock.instant().plus(indexingProperties.leaseDuration()), clock.instant()));
            if (!published) {
                jobs.supersede(job.id(), job.attempts(), "SOURCE_OR_APPROVAL_CHANGED", clock.instant());
                return true;
            }
            jobs.succeed(job.id(), job.attempts(), clock.instant());
        } catch (RuntimeException failure) {
            Failure safe = safeFailure(failure);
            if (indexedSnapshot != null) {
                try {
                    store.mark(job.documentId(), indexedSnapshot.version(), "FAILED", safe.code(),
                            indexedSnapshot.sourceHash(), indexedSnapshot.chunks().size(),
                            job.documentGeneration());
                } catch (RuntimeException publicationFailure) {
                    failure.addSuppressed(publicationFailure);
                }
            }
            Instant failedAt = clock.instant();
            boolean retryable = safe.retryable() && job.attempts() < indexingProperties.maxAttempts();
            Instant nextAttempt = failedAt;
            if (retryable) {
                Duration delay = indexingProperties.retryDelay(job.attempts(), safe.retryAfter());
                Optional<Instant> scheduled = retryAt(failedAt, delay);
                if (scheduled.isPresent()) {
                    nextAttempt = scheduled.get();
                } else {
                    retryable = false;
                }
            }
            jobs.fail(job.id(), job.attempts(), safe.code(), retryable, nextAttempt,
                    indexingProperties.maxAttempts(), failedAt);
            logger.warn("Semantic index job failed: jobId={}, documentId={}, attempt={}, code={}",
                    job.id(), job.documentId(), job.attempts(), safe.code());
        }
        return true;
    }

    private Failure safeFailure(RuntimeException failure) {
        if (failure instanceof SemanticProviderException provider) {
            return new Failure(provider.safeFailureCode(), provider.retryable(), provider.retryAfter());
        }
        if (failure instanceof BusinessException business) {
            ErrorCode code = business.getErrorCode();
            if (code == ErrorCode.AI_TIMEOUT || code == ErrorCode.AI_PROVIDER_UNAVAILABLE) {
                return new Failure(code.name(), true, null);
            }
            if (code == ErrorCode.AI_INVALID_RESPONSE || code == ErrorCode.VALIDATION_FAILED) {
                return new Failure(code.name(), false, null);
            }
        }
        if (failure instanceof DataAccessException) {
            return new Failure("INDEX_STORE_UNAVAILABLE", true, null);
        }
        return new Failure("INDEXING_FAILURE", true, null);
    }

    static Optional<Instant> retryAt(Instant failedAt, Duration delay) {
        if (failedAt == null || delay == null || delay.isNegative()
                || failedAt.isAfter(MAX_MYSQL_DATETIME)) {
            return Optional.empty();
        }
        Duration maximumDelay = Duration.between(failedAt, MAX_MYSQL_DATETIME);
        if (delay.compareTo(maximumDelay) > 0) return Optional.empty();
        try {
            return Optional.of(failedAt.plus(delay));
        } catch (DateTimeException | ArithmeticException outOfRange) {
            return Optional.empty();
        }
    }

    private record Failure(String code, boolean retryable, Duration retryAfter) { }
}
