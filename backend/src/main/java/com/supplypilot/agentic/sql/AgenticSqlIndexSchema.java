package com.supplypilot.agentic.sql;

import java.util.List;

public record AgenticSqlIndexSchema(String name, boolean unique, List<String> columns, String type) {
    public AgenticSqlIndexSchema {
        columns = List.copyOf(columns);
    }
}
