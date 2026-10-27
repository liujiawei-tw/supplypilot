package com.supplypilot.agentic.service;

import java.util.Set;

/** A sanitized value returned by an executed Agentic SQL query, addressable only within one request. */
public record AgenticSqlEvidenceCell(
        String toolCallId,
        String database,
        Set<String> tables,
        int rowIndex,
        String column,
        String value
) {
    public AgenticSqlEvidenceCell {
        tables = Set.copyOf(tables);
    }
}
