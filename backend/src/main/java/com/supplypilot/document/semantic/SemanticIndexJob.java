package com.supplypilot.document.semantic;

import java.time.Instant;

public record SemanticIndexJob(
        long id,
        long documentId,
        String version,
        String sourceHash,
        String generationKey,
        long documentGeneration,
        SemanticIndexJobStatus status,
        int attempts,
        int retryCount,
        Instant nextAttemptAt,
        Instant leaseUntil,
        String failureCode,
        Instant createdAt,
        Instant updatedAt
) { }
