package com.supplypilot.agentic.sql;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** A single parsed and validated query with an enforced outer row limit. */
public record AgenticSqlValidatedStatement(
        AgenticSqlDatabase database,
        String sql,
        List<AgenticSqlTableReference> tables,
        Map<Integer, List<String>> sensitiveProjectionColumns,
        Set<String> sensitiveColumns,
        boolean maskAllOutput
) {
    public AgenticSqlValidatedStatement {
        tables = List.copyOf(tables);
        sensitiveProjectionColumns = sensitiveProjectionColumns.entrySet().stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey,
                        entry -> List.copyOf(entry.getValue())));
        sensitiveColumns = Set.copyOf(sensitiveColumns);
    }
}
