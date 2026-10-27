package com.supplypilot.agentic.sql;

import java.util.List;

/** Bounded, safe schema metadata returned to one tool call. */
public record AgenticSqlSchemaResult(
        String toolCallId,
        AgenticSqlDatabase database,
        List<AgenticSqlTableSchema> tables
) {
    public AgenticSqlSchemaResult {
        tables = List.copyOf(tables);
    }
}
