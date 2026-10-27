package com.supplypilot.agentic.sql;

public record AgenticSqlForeignKey(
        String column,
        String referencedSchema,
        String referencedTable,
        String referencedColumn
) {
}
