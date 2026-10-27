package com.supplypilot.agentic.sql;

import java.util.List;

public record AgenticSqlTableSchema(
        String schema,
        String name,
        List<AgenticSqlColumnSchema> columns,
        List<String> primaryKeyColumns,
        List<AgenticSqlForeignKey> foreignKeys,
        List<AgenticSqlIndexSchema> indexes,
        String classification,
        String authoritativeSource,
        String warning,
        String remarks
) {
    public AgenticSqlTableSchema {
        columns = List.copyOf(columns);
        primaryKeyColumns = List.copyOf(primaryKeyColumns);
        foreignKeys = List.copyOf(foreignKeys);
        indexes = List.copyOf(indexes);
    }
}
