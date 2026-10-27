package com.supplypilot.agentic.sql;

public record AgenticSqlColumnSchema(String name, String type, boolean nullable, int ordinalPosition,
                                     String remarks) {
}
