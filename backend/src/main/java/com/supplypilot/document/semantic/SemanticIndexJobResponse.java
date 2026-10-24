package com.supplypilot.document.semantic;

import java.time.Instant;

public record SemanticIndexJobResponse(
        long id,
        long documentId,
        String version,
        SemanticIndexJobStatus status,
        int attempts,
        int maxAttempts,
        int retryCount,
        Instant nextAttemptAt,
        String failureCode,
        Instant updatedAt
) {
    public static SemanticIndexJobResponse from(SemanticIndexJob job, int maxAttempts) {
        return new SemanticIndexJobResponse(job.id(), job.documentId(), job.version(), job.status(),
                job.attempts(), maxAttempts, job.retryCount(), job.nextAttemptAt(), job.failureCode(),
                job.updatedAt());
    }
}
