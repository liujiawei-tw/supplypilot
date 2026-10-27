package com.supplypilot.agentic.sql;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.supplypilot.agentic.security.AgenticSqlOutputPolicy;
import org.springframework.stereotype.Component;

/** Dynamic metadata filtering for system, workflow-internal, and credential-bearing schema objects. */
@Component
public final class AgenticSqlMetadataPolicy {
    private static final Set<String> EXCLUDED_TABLES = Set.of(
            "users", "system_initialization", "ai_monthly_token_usage", "ai_purchase_draft_proposals",
            "demo_dataset_state", "demo_corpus_sync_state", "demo_corpus_sync_outcomes",
            "demo_corpus_sync_conflicts", "semantic_generations", "semantic_gate_audit",
            "semantic_flyway_history", "flyway_schema_history", "schema_version"
    );
    private static final Pattern EXCLUDED_TABLE_NAME = Pattern.compile(
            "(?i)(?:^|_)(?:credential|credentials|secret|secrets|token|tokens|auth|authentication|authorization|"
                    + "system_config|security_config|api[_-]?keys?|access[_-]?keys?)(?:$|_)"
    );
    private static final Pattern EXCLUDED_INTERNAL_COLUMN_NAME = Pattern.compile(
            "(?i).*(?:storage[_-]?path|embedding|semantic_.*|payload_json).*"
    );
    private static final Set<String> POSTGRES_SYSTEM_SCHEMAS = Set.of(
            "information_schema", "pg_catalog", "pg_toast", "pg_temp", "pg_toast_temp"
    );

    public boolean isSchemaVisible(AgenticSqlDatabase database, String schema) {
        if (database == AgenticSqlDatabase.MYSQL) return true;
        if (schema == null || schema.isBlank()) return false;
        String normalized = normalize(schema);
        return !POSTGRES_SYSTEM_SCHEMAS.contains(normalized)
                && !normalized.startsWith("pg_temp_")
                && !normalized.startsWith("pg_toast_temp_");
    }

    public boolean isTableVisible(AgenticSqlDatabase database, String schema, String table) {
        if (table == null || table.isBlank()) return false;
        if (database == AgenticSqlDatabase.POSTGRESQL && schema != null && !isSchemaVisible(database, schema)) return false;
        String normalized = normalize(table);
        if (EXCLUDED_TABLES.contains(normalized) || EXCLUDED_TABLE_NAME.matcher(normalized).find()) return false;
        if (normalized.startsWith("flyway_") || normalized.endsWith("_flyway_history")) return false;
        if (normalized.startsWith("semantic_")
                && !Set.of("semantic_chunks", "semantic_document_indexes").contains(normalized)) return false;
        return true;
    }

    public boolean isColumnVisible(String column) {
        return column != null && !column.isBlank()
                && !AgenticSqlOutputPolicy.isNeverSendColumnName(column)
                && !EXCLUDED_INTERNAL_COLUMN_NAME.matcher(column).matches();
    }

    public boolean isSensitiveColumn(String column) {
        return AgenticSqlOutputPolicy.isSensitiveIdentifierColumn(column);
    }

    public boolean isDerivedSemanticTable(String table) {
        return table != null && Set.of("semantic_chunks", "semantic_document_indexes").contains(normalize(table));
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}
