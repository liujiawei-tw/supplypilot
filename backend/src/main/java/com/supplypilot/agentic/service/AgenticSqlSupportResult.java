package com.supplypilot.agentic.service;

import java.util.List;

/** Deterministic disposition of model claims against this request's executed result cells. */
public record AgenticSqlSupportResult(
        String answer,
        int unsupportedClaims,
        boolean malformed,
        boolean conflictingSources,
        List<String> failureCategories
) {
    public AgenticSqlSupportResult {
        failureCategories = failureCategories == null ? List.of() : List.copyOf(failureCategories);
    }

    public boolean fullySupported() {
        return answer != null && !answer.isBlank() && unsupportedClaims == 0 && !malformed
                && !conflictingSources;
    }
}
