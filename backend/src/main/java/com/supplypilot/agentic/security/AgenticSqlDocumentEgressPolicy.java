package com.supplypilot.agentic.security;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.supplypilot.agentic.sql.AgenticSqlException;
import com.supplypilot.agentic.sql.AgenticSqlValidatedStatement;

/**
 * Agentic SQL document egress guard. It verifies live MySQL document state/version whenever a
 * query references raw document content and independently masks returned values before Gemini.
 * It does not depend on document retrieval, embeddings, vector publication, or similarity.
 */
public final class AgenticSqlDocumentEgressPolicy {
    private final AgenticSqlOutputPolicy outputPolicy;

    public AgenticSqlDocumentEgressPolicy(AgenticSqlOutputPolicy outputPolicy) {
        this.outputPolicy = outputPolicy;
    }

    public List<Map<String, Object>> inspectAndProtect(Connection mysqlConnection,
                                                         AgenticSqlValidatedStatement statement,
                                                         List<Map<String, Object>> rows) {
        if (!statement.documentContentReference()) return rows;
        if (mysqlConnection == null || statement.documentSourceTable() == null
                || statement.documentIdColumnLabel() == null || statement.documentIdColumnLabel().isBlank()
                || "semantic_chunks".equalsIgnoreCase(statement.documentSourceTable())
                    && (statement.documentVersionColumnLabel() == null
                        || statement.documentVersionColumnLabel().isBlank())) {
            throw new AgenticSqlException(AgenticSqlException.Code.DOCUMENT_EGRESS_REJECTED);
        }
        try {
            Map<Long, DocumentSnapshot> snapshots = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                long documentId = documentId(value(row, statement.documentIdColumnLabel()));
                DocumentSnapshot snapshot = snapshots.computeIfAbsent(documentId,
                        id -> readCurrentDocument(mysqlConnection, id));
                if (!"READY".equalsIgnoreCase(snapshot.status()) || snapshot.version() == null
                        || snapshot.version().isBlank()) {
                    throw new AgenticSqlException(AgenticSqlException.Code.DOCUMENT_EGRESS_REJECTED);
                }
                if ("semantic_chunks".equalsIgnoreCase(statement.documentSourceTable())) {
                    Object sourceVersion = value(row, statement.documentVersionColumnLabel());
                    if (sourceVersion == null || !snapshot.version().equals(sourceVersion.toString())) {
                        throw new AgenticSqlException(AgenticSqlException.Code.DOCUMENT_EGRESS_REJECTED);
                    }
                }
            }
            return maskTextValues(rows);
        } catch (AgenticSqlException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AgenticSqlException(AgenticSqlException.Code.DOCUMENT_EGRESS_UNAVAILABLE);
        }
    }

    private DocumentSnapshot readCurrentDocument(Connection connection, long documentId) {
        try (PreparedStatement query = connection.prepareStatement(
                "SELECT version, status FROM document_metadata WHERE id = ?")) {
            query.setLong(1, documentId);
            query.setQueryTimeout(5);
            try (ResultSet row = query.executeQuery()) {
                if (!row.next()) throw new AgenticSqlException(AgenticSqlException.Code.DOCUMENT_EGRESS_REJECTED);
                return new DocumentSnapshot(row.getString("version"), row.getString("status"));
            }
        } catch (AgenticSqlException exception) {
            throw exception;
        } catch (SQLException exception) {
            throw new AgenticSqlException(AgenticSqlException.Code.DOCUMENT_EGRESS_UNAVAILABLE);
        }
    }

    private List<Map<String, Object>> maskTextValues(List<Map<String, Object>> rows) {
        List<Map<String, Object>> protectedRows = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            LinkedHashMap<String, Object> protectedRow = new LinkedHashMap<>();
            row.forEach((column, value) -> protectedRow.put(column,
                    value instanceof String text ? outputPolicy.maskColumnValue(column, text) : value));
            protectedRows.add(protectedRow);
        }
        return List.copyOf(protectedRows);
    }

    private Object value(Map<String, Object> row, String label) {
        for (Map.Entry<String, Object> cell : row.entrySet()) {
            if (cell.getKey().equalsIgnoreCase(label)) return cell.getValue();
        }
        return null;
    }

    private long documentId(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(value == null ? "" : value.toString());
        } catch (NumberFormatException exception) {
            throw new AgenticSqlException(AgenticSqlException.Code.DOCUMENT_EGRESS_REJECTED);
        }
    }

    private record DocumentSnapshot(String version, String status) { }
}
