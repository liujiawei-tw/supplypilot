package com.supplypilot.document.dto.response;

import java.time.Instant;

import com.supplypilot.document.domain.SemanticReviewStatus;

public record DocumentSemanticReviewResponse(
        SemanticReviewStatus status,
        String sourceHash,
        String version,
        String reason,
        String actorType,
        String actor,
        String trigger,
        String policyDecision,
        Instant reviewedAt,
        boolean valid,
        Instant invalidatedAt,
        String invalidationReason
) { }

