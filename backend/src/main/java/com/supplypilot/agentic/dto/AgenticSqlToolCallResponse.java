package com.supplypilot.agentic.dto;

import java.util.List;

public record AgenticSqlToolCallResponse(
        String toolCallId,
        String operation,
        String database,
        List<String> tables,
        Integer rowsReturned,
        boolean truncated,
        long elapsedMs,
        String status,
        String errorCode
) {
}
