package com.supplypilot.agentic.dto;

import java.util.List;
import java.util.Set;

public record AgenticSqlQueryResponse(
        int version,
        AgenticSqlQueryStatus status,
        String answer,
        List<AgenticSqlSourceResponse> sources,
        Set<String> usedDatabases,
        List<AgenticSqlToolCallResponse> toolCalls,
        long latencyMs,
        AgenticSqlUsage usage,
        List<String> warnings
) {
    public AgenticSqlQueryResponse {
        sources = List.copyOf(sources);
        usedDatabases = Set.copyOf(usedDatabases);
        toolCalls = List.copyOf(toolCalls);
        warnings = List.copyOf(warnings);
    }
}
