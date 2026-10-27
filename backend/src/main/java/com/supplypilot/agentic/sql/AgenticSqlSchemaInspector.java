package com.supplypilot.agentic.sql;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.supplypilot.agentic.config.AgenticSqlDataSources;
import com.supplypilot.agentic.config.AgenticSqlProperties;
import com.supplypilot.agentic.security.AgenticSqlOutputPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Dynamic JDBC metadata discovery, restricted to non-system business schemas and safe columns. */
@Component
@ConditionalOnProperty(prefix = "supplypilot.agentic.sql", name = "enabled", havingValue = "true")
public final class AgenticSqlSchemaInspector {
    private static final String[] DISCOVERABLE_TABLE_TYPES = {"TABLE", "VIEW", "MATERIALIZED VIEW"};
    private static final int MAX_REMARK_LENGTH = 240;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int MAX_OFFSET = 100_000;

    private final AgenticSqlDataSources dataSources;
    private final AgenticSqlProperties properties;
    private final AgenticSqlMetadataPolicy metadataPolicy;
    private final AgenticSqlOutputPolicy outputPolicy;
    private final AgenticSqlConnectionProvider connections;

    @Autowired
    public AgenticSqlSchemaInspector(AgenticSqlDataSources dataSources,
                                     AgenticSqlProperties properties,
                                     AgenticSqlMetadataPolicy metadataPolicy,
                                     AgenticSqlOutputPolicy outputPolicy) {
        this.dataSources = dataSources;
        this.properties = properties;
        this.metadataPolicy = metadataPolicy;
        this.outputPolicy = outputPolicy;
        this.connections = database -> switch (database) {
            case MYSQL -> dataSources.getMysqlConnection();
            case POSTGRESQL -> dataSources.getPostgresConnection();
        };
    }

    AgenticSqlSchemaInspector(AgenticSqlConnectionProvider connections,
                              AgenticSqlProperties properties,
                              AgenticSqlMetadataPolicy metadataPolicy,
                              AgenticSqlOutputPolicy outputPolicy) {
        this.dataSources = null;
        this.properties = properties;
        this.metadataPolicy = metadataPolicy;
        this.outputPolicy = outputPolicy;
        this.connections = connections;
    }

    public AgenticSqlSchemaResult inspectSchema(String toolCallId, AgenticSqlDatabase database,
                                                String requestedSchema, String requestedTable) {
        return inspectSchema(toolCallId, database, requestedSchema, requestedTable, 0,
                Math.min(MAX_PAGE_SIZE, properties.maxRows()));
    }

    public AgenticSqlSchemaResult inspectSchema(String toolCallId, AgenticSqlDatabase database,
                                                String requestedSchema, String requestedTable,
                                                int offset, int requestedPageSize) {
        if (toolCallId == null || toolCallId.isBlank() || database == null) {
            throw new AgenticSqlException(AgenticSqlException.Code.INVALID_SQL);
        }
        if (offset < 0 || offset > MAX_OFFSET || requestedPageSize < 1) {
            throw new AgenticSqlException(AgenticSqlException.Code.INVALID_SQL);
        }
        int pageSize = Math.min(MAX_PAGE_SIZE, Math.min(properties.maxRows(), requestedPageSize));
        try (Connection connection = connections.open(database)) {
            connection.setReadOnly(true);
            DatabaseMetaData metadata = connection.getMetaData();
            String currentCatalog = connection.getCatalog();
            List<VisibleTable> matching = listVisibleTables(metadata, database, currentCatalog).stream()
                    .filter(table -> matches(requestedSchema,
                            database == AgenticSqlDatabase.MYSQL ? currentCatalog : table.schema()))
                    .filter(table -> matches(requestedTable, table.name()))
                    .sorted(Comparator.comparing((VisibleTable table) ->
                                    database == AgenticSqlDatabase.MYSQL ? currentCatalog : table.schema(),
                                    Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER))
                            .thenComparing(VisibleTable::name, String.CASE_INSENSITIVE_ORDER))
                    .toList();
            int start = Math.min(offset, matching.size());
            int end = Math.min(matching.size(), start + pageSize);
            List<AgenticSqlTableSchema> tables = new ArrayList<>();
            for (VisibleTable table : matching.subList(start, end)) {
                String effectiveSchema = database == AgenticSqlDatabase.MYSQL ? currentCatalog : table.schema();
                tables.add(readTable(metadata, database, currentCatalog, effectiveSchema, table.name(), null));
            }
            boolean hasMore = end < matching.size();
            return new AgenticSqlSchemaResult(toolCallId, database, tables, offset, pageSize,
                    hasMore, hasMore ? end : null);
        } catch (AgenticSqlException exception) {
            throw exception;
        } catch (SQLException exception) {
            throw new AgenticSqlException(AgenticSqlException.Code.DATABASE_UNAVAILABLE);
        }
    }

    /** Query validation is against the same live metadata view that powers schema discovery. */
    List<AgenticSqlTableReference> resolveQueryableTables(Connection connection, AgenticSqlDatabase database,
                                                          List<AgenticSqlTableReference> references)
            throws SQLException {
        if (references.isEmpty()) return List.of();
        DatabaseMetaData metadata = connection.getMetaData();
        String currentCatalog = connection.getCatalog();
        List<VisibleTable> visible = listVisibleTables(metadata, database, currentCatalog);
        List<AgenticSqlTableReference> resolved = new ArrayList<>();
        for (AgenticSqlTableReference reference : references) {
            if (!metadataPolicy.isTableVisible(database, reference.schema(), reference.name())) {
                throw new AgenticSqlException(AgenticSqlException.Code.UNSAFE_SQL);
            }
            List<VisibleTable> matches = visible.stream().filter(table ->
                    table.name().equalsIgnoreCase(reference.name())
                            && (reference.catalog() == null || reference.catalog().equalsIgnoreCase(currentCatalog))
                            && (reference.schema() == null || reference.schema().equalsIgnoreCase(table.schema())))
                    .toList();
            if (matches.size() != 1) throw new AgenticSqlException(AgenticSqlException.Code.UNSAFE_SQL);
            VisibleTable match = matches.getFirst();
            resolved.add(new AgenticSqlTableReference(match.catalog(), match.schema(), match.name(), reference.alias()));
        }
        return List.copyOf(resolved);
    }

    private List<VisibleTable> listVisibleTables(DatabaseMetaData metadata, AgenticSqlDatabase database,
                                                String currentCatalog) throws SQLException {
        List<VisibleTable> visible = new ArrayList<>();
        for (String schema : visibleSchemas(database, metadata, currentCatalog, null)) {
            try (ResultSet rows = metadata.getTables(currentCatalog,
                    database == AgenticSqlDatabase.MYSQL ? null : schema, null, DISCOVERABLE_TABLE_TYPES)) {
                while (rows.next()) {
                    String name = rows.getString("TABLE_NAME");
                    String actualSchema = database == AgenticSqlDatabase.MYSQL
                            ? null : rows.getString("TABLE_SCHEM");
                    String actualCatalog = rows.getString("TABLE_CAT");
                    if (isAllowedTableType(rows.getString("TABLE_TYPE"))
                            && metadataPolicy.isTableVisible(database, actualSchema, name)) {
                        visible.add(new VisibleTable(actualCatalog, actualSchema, name));
                    }
                }
            }
        }
        return visible;
    }

    private List<String> visibleSchemas(AgenticSqlDatabase database, DatabaseMetaData metadata,
                                        String currentCatalog, String requestedSchema) throws SQLException {
        if (database == AgenticSqlDatabase.MYSQL) {
            if (currentCatalog == null || currentCatalog.isBlank()
                    || requestedSchema != null && !requestedSchema.isBlank()
                    && !currentCatalog.equalsIgnoreCase(requestedSchema)) return List.of();
            return List.of(currentCatalog);
        }
        List<String> schemas = new ArrayList<>();
        try (ResultSet rows = metadata.getSchemas(currentCatalog, null)) {
            while (rows.next()) {
                String schema = rows.getString("TABLE_SCHEM");
                if (metadataPolicy.isSchemaVisible(database, schema) && matches(requestedSchema, schema)) {
                    schemas.add(schema);
                }
            }
        }
        return schemas;
    }

    private AgenticSqlTableSchema readTable(DatabaseMetaData metadata, AgenticSqlDatabase database,
                                            String currentCatalog, String schema, String table,
                                            String tableRemarks) throws SQLException {
        List<AgenticSqlColumnSchema> columns = new ArrayList<>();
        try (ResultSet rows = metadata.getColumns(currentCatalog,
                database == AgenticSqlDatabase.MYSQL ? null : schema, table, null)) {
            while (rows.next()) {
                String column = rows.getString("COLUMN_NAME");
                if (!metadataPolicy.isColumnVisible(column)) continue;
                String remarks = safeRemark(column, rows.getString("REMARKS"));
                columns.add(new AgenticSqlColumnSchema(column, rows.getString("TYPE_NAME"),
                        rows.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                        rows.getInt("ORDINAL_POSITION"), remarks));
            }
        }
        columns.sort(Comparator.comparingInt(AgenticSqlColumnSchema::ordinalPosition));

        TreeMap<Short, String> primaryKeysByPosition = new TreeMap<>();
        try (ResultSet rows = metadata.getPrimaryKeys(currentCatalog,
                database == AgenticSqlDatabase.MYSQL ? null : schema, table)) {
            while (rows.next()) {
                String column = rows.getString("COLUMN_NAME");
                if (metadataPolicy.isColumnVisible(column)) {
                    primaryKeysByPosition.put(rows.getShort("KEY_SEQ"), column);
                }
            }
        }
        List<String> primaryKeys = List.copyOf(primaryKeysByPosition.values());
        List<AgenticSqlForeignKey> foreignKeys = readForeignKeys(metadata, database, currentCatalog, schema, table);
        List<AgenticSqlIndexSchema> indexes = readIndexes(metadata, database, currentCatalog, schema, table);
        String classification = "DATABASE_TABLE";
        String authoritativeSource = null;
        String warning = null;
        if (database == AgenticSqlDatabase.POSTGRESQL && metadataPolicy.isDerivedSemanticTable(table)) {
            classification = "DERIVED_INDEX";
            authoritativeSource = "MYSQL document_chunks";
            warning = "This semantic index may be missing or stale; MySQL document_chunks is authoritative.";
        }
        return new AgenticSqlTableSchema(schema, table, columns, primaryKeys, foreignKeys, indexes,
                classification, authoritativeSource, warning, safeRemark("remarks", tableRemarks));
    }

    private List<AgenticSqlForeignKey> readForeignKeys(DatabaseMetaData metadata, AgenticSqlDatabase database,
                                                       String catalog, String schema, String table)
            throws SQLException {
        List<AgenticSqlForeignKey> result = new ArrayList<>();
        try (ResultSet rows = metadata.getImportedKeys(catalog,
                database == AgenticSqlDatabase.MYSQL ? null : schema, table)) {
            while (rows.next()) {
                String column = rows.getString("FKCOLUMN_NAME");
                String referencedColumn = rows.getString("PKCOLUMN_NAME");
                String referencedTable = rows.getString("PKTABLE_NAME");
                String referencedSchema = rows.getString("PKTABLE_SCHEM");
                if (metadataPolicy.isColumnVisible(column) && metadataPolicy.isColumnVisible(referencedColumn)
                        && metadataPolicy.isTableVisible(database, referencedSchema, referencedTable)) {
                    result.add(new AgenticSqlForeignKey(column, referencedSchema, referencedTable, referencedColumn));
                }
            }
        }
        return List.copyOf(result);
    }

    private List<AgenticSqlIndexSchema> readIndexes(DatabaseMetaData metadata, AgenticSqlDatabase database,
                                                    String catalog, String schema, String table)
            throws SQLException {
        Map<String, IndexBuilder> builders = new LinkedHashMap<>();
        try (ResultSet rows = metadata.getIndexInfo(catalog,
                database == AgenticSqlDatabase.MYSQL ? null : schema, table, false, false)) {
            while (rows.next()) {
                String name = rows.getString("INDEX_NAME");
                String column = rows.getString("COLUMN_NAME");
                if (name == null || column == null) continue;
                IndexBuilder builder = builders.computeIfAbsent(name, ignored ->
                        new IndexBuilder(name, !safeGetBoolean(rows, "NON_UNIQUE"), safeGetString(rows, "TYPE")));
                if (!metadataPolicy.isColumnVisible(column)) {
                    builder.hasHiddenColumn = true;
                    continue;
                }
                builder.columns.put(rows.getShort("ORDINAL_POSITION"), column);
            }
        }
        return builders.values().stream().filter(builder -> !builder.hasHiddenColumn && !builder.columns.isEmpty())
                .map(IndexBuilder::toSchema).toList();
    }

    private String safeRemark(String column, String value) {
        if (value == null || value.isBlank() || !metadataPolicy.isColumnVisible(column)) return null;
        String bounded = value.length() > MAX_REMARK_LENGTH ? value.substring(0, MAX_REMARK_LENGTH) : value;
        String masked = outputPolicy.maskColumnValue("remarks", bounded);
        return masked.length() > MAX_REMARK_LENGTH ? masked.substring(0, MAX_REMARK_LENGTH) : masked;
    }

    private boolean isAllowedTableType(String tableType) {
        return tableType != null && List.of(DISCOVERABLE_TABLE_TYPES).stream()
                .anyMatch(allowed -> allowed.equalsIgnoreCase(tableType));
    }

    private boolean matches(String requested, String actual) {
        return requested == null || requested.isBlank() || actual != null && requested.equalsIgnoreCase(actual);
    }

    private static String safeGetString(ResultSet rows, String column) {
        try {
            return rows.getString(column);
        } catch (SQLException exception) {
            return null;
        }
    }

    private static boolean safeGetBoolean(ResultSet rows, String column) {
        try {
            return rows.getBoolean(column);
        } catch (SQLException exception) {
            return true;
        }
    }

    private record VisibleTable(String catalog, String schema, String name) {
    }

    private static final class IndexBuilder {
        private final String name;
        private final boolean unique;
        private final String type;
        private final TreeMap<Short, String> columns = new TreeMap<>();
        private boolean hasHiddenColumn;

        private IndexBuilder(String name, boolean unique, String type) {
            this.name = name;
            this.unique = unique;
            this.type = type;
        }

        private AgenticSqlIndexSchema toSchema() {
            return new AgenticSqlIndexSchema(name, unique, new ArrayList<>(columns.values()), type);
        }
    }
}
