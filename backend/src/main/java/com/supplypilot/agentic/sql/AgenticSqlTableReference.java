package com.supplypilot.agentic.sql;

/** A physical table reference collected from the parsed SQL AST. */
public record AgenticSqlTableReference(String catalog, String schema, String name, String alias) {
    public AgenticSqlTableReference {
        catalog = normalize(catalog);
        schema = normalize(schema);
        name = normalize(name);
        alias = normalize(alias);
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public String qualifiedName() {
        if (schema != null) return schema + "." + name;
        if (catalog != null) return catalog + "." + name;
        return name;
    }
}
