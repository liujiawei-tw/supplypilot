package com.supplypilot.agentic.dto;

import java.util.Map;

public record AgenticSqlSourceResponse(
        String database,
        String schema,
        String table,
        String toolCallId,
        Integer resultRowIndex,
        Map<String, String> primaryKeyValues
) {
}
