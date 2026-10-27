package com.supplypilot.agentic.sql;

import java.util.List;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Bounded SQL tool output and any row-level primary-key evidence the driver can verify. */
public record AgenticSqlExecutionResult(
        String toolCallId,
        AgenticSqlDatabase database,
        List<String> tables,
        List<String> columns,
        List<Map<String, Object>> rows,
        Long rowCount,
        boolean truncated,
        long elapsedMs,
        List<AgenticSqlSourceRow> sourceRows,
        Map<String, Set<String>> projectionSources
) {
    public AgenticSqlExecutionResult {
        tables = List.copyOf(tables);
        columns = List.copyOf(columns);
        rows = rows.stream().map(row -> Collections.unmodifiableMap(new LinkedHashMap<>(row))).toList();
        sourceRows = List.copyOf(sourceRows);
        projectionSources = projectionSources.entrySet().stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey,
                        entry -> Set.copyOf(entry.getValue())));
    }

    public AgenticSqlExecutionResult(String toolCallId, AgenticSqlDatabase database, List<String> tables,
                                     List<String> columns, List<Map<String, Object>> rows, Long rowCount,
                                     boolean truncated, long elapsedMs, List<AgenticSqlSourceRow> sourceRows) {
        this(toolCallId, database, tables, columns, rows, rowCount, truncated, elapsedMs, sourceRows, Map.of());
    }
}
