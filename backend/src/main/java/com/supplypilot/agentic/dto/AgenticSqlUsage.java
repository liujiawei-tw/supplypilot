package com.supplypilot.agentic.dto;

public record AgenticSqlUsage(
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        String estimatedCost
) {
    public static AgenticSqlUsage unavailable() {
        return new AgenticSqlUsage(null, null, null, "N/A");
    }
}
