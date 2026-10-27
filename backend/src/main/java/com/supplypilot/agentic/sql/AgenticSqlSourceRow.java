package com.supplypilot.agentic.sql;

import java.util.Map;

/** A result row mapped back to a physical table primary key when the query selected that key. */
public record AgenticSqlSourceRow(
        int resultRowIndex,
        String schema,
        String table,
        Map<String, String> primaryKeyValues
) {
    public AgenticSqlSourceRow {
        primaryKeyValues = Map.copyOf(primaryKeyValues);
    }
}
