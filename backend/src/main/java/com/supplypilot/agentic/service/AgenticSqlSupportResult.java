package com.supplypilot.agentic.service;

/** Deterministic disposition of model claims against this request's executed result cells. */
public record AgenticSqlSupportResult(
        String answer,
        int unsupportedClaims,
        boolean malformed,
        boolean conflictingSources
) {
    public boolean fullySupported() {
        return answer != null && !answer.isBlank() && unsupportedClaims == 0 && !malformed
                && !conflictingSources;
    }
}
