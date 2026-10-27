package com.supplypilot.agentic.sql;

import java.util.List;

/** Bounded, safe schema metadata returned to one tool call. */
public record AgenticSqlSchemaResult(
        String toolCallId,
        AgenticSqlDatabase database,
        List<AgenticSqlTableSchema> tables,
        int offset,
        int pageSize,
        boolean hasMore,
        Integer nextOffset
) {
    public AgenticSqlSchemaResult(String toolCallId, AgenticSqlDatabase database,
                                  List<AgenticSqlTableSchema> tables) {
        this(toolCallId, database, tables, 0, tables.size(), false, null);
    }

    public AgenticSqlSchemaResult {
        tables = List.copyOf(tables);
    }
}
