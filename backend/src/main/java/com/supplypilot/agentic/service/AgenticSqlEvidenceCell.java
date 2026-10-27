package com.supplypilot.agentic.service;

import java.util.Set;

/** A sanitized value returned by an executed Agentic SQL query, addressable only within one request. */
public record AgenticSqlEvidenceCell(
        String toolCallId,
        String database,
        Set<String> tables,
        int rowIndex,
        String column,
        String value,
        Set<String> sourceColumns,
        boolean sourceBacked
) {
    public AgenticSqlEvidenceCell {
        tables = Set.copyOf(tables);
        sourceColumns = Set.copyOf(sourceColumns);
    }

    /** Compatibility constructor for trusted in-process fixtures; production supplies SQL lineage explicitly. */
    public AgenticSqlEvidenceCell(String toolCallId, String database, Set<String> tables,
                                  int rowIndex, String column, String value) {
        this(toolCallId, database, tables, rowIndex, column, value,
                tables.stream().map(table -> table + "." + column).collect(java.util.stream.Collectors.toSet()), true);
    }
}
