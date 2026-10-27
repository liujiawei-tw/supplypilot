package com.supplypilot.agentic.sql;

import java.io.IOException;
import java.io.Reader;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import com.supplypilot.agentic.config.AgenticSqlDataSources;
import com.supplypilot.agentic.config.AgenticSqlProperties;
import com.supplypilot.agentic.security.AgenticSqlOutputPolicy;
import com.supplypilot.agentic.security.AgenticSqlDocumentEgressPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Read-only JDBC execution with parser validation and bounded results for both supported dialects. */
@Component
@ConditionalOnProperty(prefix = "supplypilot.agentic.sql", name = "enabled", havingValue = "true")
public final class AgenticSqlExecutor {
    private static final String BINARY_OMITTED = "[BINARY_OMITTED]";
    private static final String UNSUPPORTED_VALUE = "[UNSUPPORTED_VALUE]";

    private final AgenticSqlProperties properties;
    private final AgenticSqlStatementValidator validator;
    private final AgenticSqlSchemaInspector schemaInspector;
    private final AgenticSqlConnectionProvider connections;
    private final AgenticSqlMetadataPolicy metadataPolicy;
    private final AgenticSqlOutputPolicy outputPolicy;
    private final AgenticSqlDocumentEgressPolicy documentEgressPolicy;

    @Autowired
    public AgenticSqlExecutor(AgenticSqlDataSources dataSources,
                              AgenticSqlProperties properties,
                              AgenticSqlStatementValidator validator,
                              AgenticSqlSchemaInspector schemaInspector,
                              AgenticSqlMetadataPolicy metadataPolicy,
                              AgenticSqlOutputPolicy outputPolicy) {
        this(database -> switch (database) {
            case MYSQL -> dataSources.getMysqlConnection();
            case POSTGRESQL -> dataSources.getPostgresConnection();
        }, properties, validator, schemaInspector, metadataPolicy, outputPolicy);
    }

    AgenticSqlExecutor(AgenticSqlConnectionProvider connections,
                       AgenticSqlProperties properties,
                       AgenticSqlStatementValidator validator,
                       AgenticSqlSchemaInspector schemaInspector,
                       AgenticSqlMetadataPolicy metadataPolicy,
                       AgenticSqlOutputPolicy outputPolicy) {
        this.connections = connections;
        this.properties = properties;
        this.validator = validator;
        this.schemaInspector = schemaInspector;
        this.metadataPolicy = metadataPolicy;
        this.outputPolicy = outputPolicy;
        this.documentEgressPolicy = new AgenticSqlDocumentEgressPolicy(outputPolicy);
    }

    public AgenticSqlExecutionResult execute(String toolCallId, AgenticSqlDatabase database, String sql) {
        if (toolCallId == null || toolCallId.isBlank() || database == null) {
            throw new AgenticSqlException(AgenticSqlException.Code.INVALID_SQL);
        }
        long started = System.nanoTime();
        AgenticSqlValidatedStatement validated = validator.validate(database, sql, properties.maxRows());
        try (Connection connection = connections.open(database)) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            beginReadOnlyTransaction(connection, database, properties.statementTimeout());
            List<AgenticSqlTableReference> sourceTables = schemaInspector.resolveQueryableTables(
                    connection, database, validated.tables());
            try (Statement statement = connection.createStatement()) {
                statement.setQueryTimeout(timeoutSeconds(properties.statementTimeout()));
                statement.setMaxRows(properties.maxRows() + 1);
                QueryRows queryRows;
                try (ResultSet resultSet = statement.executeQuery(validated.sql())) {
                    queryRows = readRows(connection.getMetaData(), database, resultSet,
                            sourceTables, validated);
                }
                List<Map<String, Object>> returnedRows = queryRows.rows();
                if (validated.documentContentReference()) {
                    if (database == AgenticSqlDatabase.MYSQL) {
                        returnedRows = documentEgressPolicy.inspectAndProtect(connection, validated, returnedRows);
                    } else {
                        try (Connection mysqlConnection = connections.open(AgenticSqlDatabase.MYSQL)) {
                            mysqlConnection.setReadOnly(true);
                            mysqlConnection.setAutoCommit(false);
                            beginReadOnlyTransaction(mysqlConnection, AgenticSqlDatabase.MYSQL,
                                    properties.statementTimeout());
                            returnedRows = documentEgressPolicy.inspectAndProtect(
                                    mysqlConnection, validated, returnedRows);
                            mysqlConnection.rollback();
                        }
                    }
                }
                connection.rollback();
                return new AgenticSqlExecutionResult(toolCallId, database,
                        validated.tables().stream().map(AgenticSqlTableReference::qualifiedName).toList(),
                        queryRows.columns(), returnedRows, (long) returnedRows.size(), queryRows.truncated(),
                        elapsedMs(started), queryRows.sourceRows(), queryRows.projectionSources());
            }
        } catch (AgenticSqlException exception) {
            throw exception;
        } catch (SQLException exception) {
            if (isTimeout(exception)) throw new AgenticSqlException(AgenticSqlException.Code.QUERY_TIMEOUT);
            if (isConnectionFailure(exception)) throw new AgenticSqlException(AgenticSqlException.Code.DATABASE_UNAVAILABLE);
            throw new AgenticSqlException(AgenticSqlException.Code.QUERY_FAILED);
        }
    }

    private void beginReadOnlyTransaction(Connection connection, AgenticSqlDatabase database,
                                          Duration timeout) throws SQLException {
        int timeoutMillis = Math.toIntExact(Math.max(1, timeout.toMillis()));
        try (Statement setup = connection.createStatement()) {
            if (database == AgenticSqlDatabase.MYSQL) {
                setup.execute("SET SESSION max_execution_time = " + timeoutMillis);
                setup.execute("SET TRANSACTION READ ONLY");
            } else {
                setup.execute("SET TRANSACTION READ ONLY");
                setup.execute("SET LOCAL statement_timeout = '" + timeoutMillis + "ms'");
            }
        }
    }

    private QueryRows readRows(DatabaseMetaData databaseMetaData, AgenticSqlDatabase database,
                               ResultSet resultSet, List<AgenticSqlTableReference> sourceTables,
                               AgenticSqlValidatedStatement validated) throws SQLException {
        ResultSetMetaData metadata = resultSet.getMetaData();
        int totalColumns = metadata.getColumnCount();
        List<OutputColumn> outputColumns = new ArrayList<>();
        LinkedHashSet<String> labels = new LinkedHashSet<>();
        boolean truncated = totalColumns > properties.maxColumns();
        for (int index = 1; index <= totalColumns && outputColumns.size() < properties.maxColumns(); index++) {
            String label = safeColumnLabel(metadata, index);
            String physicalName = safeColumnName(metadata, index);
            if (!metadataPolicy.isColumnVisible(label) || !metadataPolicy.isColumnVisible(physicalName)) {
                truncated = true;
                continue;
            }
            String uniqueLabel = uniqueLabel(label, labels);
            java.util.Set<String> sourceColumns = validated.projectionSources().get(index);
            if (sourceColumns == null && !validated.projectionLineageAmbiguous()
                    && safeTableName(metadata, index) != null && !safeTableName(metadata, index).isBlank()
                    && physicalName != null && !physicalName.isBlank()) {
                sourceColumns = java.util.Set.of(safeTableName(metadata, index) + "." + physicalName);
            }
            if (sourceColumns == null || validated.projectionLineageAmbiguous()) sourceColumns = java.util.Set.of();
            outputColumns.add(new OutputColumn(index, uniqueLabel, physicalName,
                    safeSchemaName(metadata, index), safeTableName(metadata, index),
                    safeCatalogName(metadata, index), metadata.getColumnType(index),
                    safeTypeName(metadata, index), sourceColumns));
        }

        List<String> columns = outputColumns.stream().map(OutputColumn::label).toList();
        Map<String, java.util.Set<String>> projectionSources = outputColumns.stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(OutputColumn::label,
                        OutputColumn::sourceColumns));
        Map<TableKey, List<String>> primaryKeys = loadPrimaryKeys(databaseMetaData, database, outputColumns, sourceTables);
        List<Map<String, Object>> rows = new ArrayList<>();
        List<AgenticSqlSourceRow> sourceRows = new ArrayList<>();
        int characterCount = columns.stream().mapToInt(String::length).sum();
        while (resultSet.next()) {
            if (rows.size() >= properties.maxRows()) {
                truncated = true;
                break;
            }
            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
            Map<Integer, Object> valuesByIndex = new HashMap<>();
            boolean rowTruncated = false;
            for (OutputColumn column : outputColumns) {
                int remaining = properties.maxResultChars() - characterCount - column.label().length();
                if (remaining <= 0) {
                    truncated = true;
                    rowTruncated = true;
                    break;
                }
                Value rawValue = readValue(resultSet, column, remaining);
                Value value = maskValue(column, rawValue, remaining, validated);
                if (value.truncated()) {
                    truncated = true;
                    rowTruncated = true;
                }
                row.put(column.label(), value.value());
                valuesByIndex.put(column.index(), value.value());
                characterCount += column.label().length() + value.characters();
                if (characterCount >= properties.maxResultChars()) {
                    truncated = true;
                    rowTruncated = true;
                    break;
                }
            }
            if (!row.isEmpty()) {
                rows.add(row);
                sourceRows.addAll(sourceRows(rows.size() - 1, outputColumns, valuesByIndex, primaryKeys));
            }
            if (rowTruncated) break;
        }
        return new QueryRows(List.copyOf(columns), List.copyOf(rows), List.copyOf(sourceRows),
                projectionSources, truncated);
    }

    private Value readValue(ResultSet resultSet, OutputColumn column, int remaining) throws SQLException {
        int type = column.jdbcType();
        if (type == Types.BINARY || type == Types.VARBINARY || type == Types.LONGVARBINARY || type == Types.BLOB
                || type == Types.BIT) {
            resultSet.getObject(column.index());
            if (resultSet.wasNull()) return new Value(null, false, 0);
            return new Value(BINARY_OMITTED, true, BINARY_OMITTED.length());
        }
        if (isCharacterType(type, column.typeName())) {
            try (Reader reader = resultSet.getCharacterStream(column.index())) {
                if (reader == null) return new Value(null, false, 0);
                return readBounded(reader, remaining);
            } catch (IOException exception) {
                throw new SQLException("Unable to read bounded character column", exception);
            }
        }
        if (type == Types.ARRAY || type == Types.STRUCT || type == Types.REF || type == Types.ROWID
                || type == Types.DISTINCT || type == Types.JAVA_OBJECT) {
            resultSet.getObject(column.index());
            if (resultSet.wasNull()) return new Value(null, false, 0);
            return new Value(UNSUPPORTED_VALUE, true, UNSUPPORTED_VALUE.length());
        }
        Object value = resultSet.getObject(column.index());
        if (value == null) return new Value(null, false, 0);
        if (value instanceof byte[]) return new Value(BINARY_OMITTED, true, BINARY_OMITTED.length());
        if (value instanceof Number || value instanceof Boolean) {
            String text = value.toString();
            if (text.length() > remaining) return truncatedValue(remaining);
            return new Value(value instanceof BigDecimal bigDecimal ? bigDecimal : value, false, text.length());
        }
        if (value instanceof TemporalAccessor || value instanceof java.sql.Date || value instanceof java.sql.Time
                || value instanceof java.sql.Timestamp) {
            String text = value.toString();
            return boundedString(text, remaining);
        }
        return new Value(UNSUPPORTED_VALUE, true, UNSUPPORTED_VALUE.length());
    }

    private Value readBounded(Reader reader, int maxCharacters) throws IOException {
        int target = Math.max(0, maxCharacters);
        StringBuilder value = new StringBuilder(Math.min(target, 512));
        char[] buffer = new char[Math.min(1024, Math.max(1, target + 1))];
        while (value.length() <= target) {
            int requested = Math.min(buffer.length, target + 1 - value.length());
            int count = reader.read(buffer, 0, requested);
            if (count < 0) return new Value(value.toString(), false, value.length());
            value.append(buffer, 0, count);
            if (value.length() > target) return truncatedValue(target);
        }
        return new Value(value.toString(), true, value.length());
    }

    private Value boundedString(String text, int remaining) {
        if (text.length() > remaining) return truncatedValue(remaining);
        return new Value(text, false, text.length());
    }

    private Value maskValue(OutputColumn column, Value value, int remaining,
                            AgenticSqlValidatedStatement validated) {
        if (value.value() == null) return value;
        if (validated.maskAllOutput()) {
            String marker = "[SENSITIVE_DATA_REDACTED]";
            if (marker.length() > remaining) return truncatedValue(remaining);
            return new Value(marker, value.truncated(), marker.length());
        }
        String physicalName = column.physicalColumn() == null || column.physicalColumn().isBlank()
                ? column.label() : column.physicalColumn();
        List<String> maskColumns = validated.sensitiveProjectionColumns().getOrDefault(column.index(), List.of());
        if (maskColumns.isEmpty()) maskColumns = List.of(physicalName);
        String masked = value.value().toString();
        for (String maskColumn : maskColumns) masked = outputPolicy.maskColumnValue(maskColumn, masked);
        if (masked.length() > remaining) return truncatedValue(remaining);
        return new Value(masked, value.truncated(), masked.length());
    }

    private Value truncatedValue() {
        String marker = "[VALUE_TRUNCATED]";
        return new Value(marker, true, marker.length());
    }

    private Value truncatedValue(int remaining) {
        String marker = "[VALUE_TRUNCATED]";
        int length = Math.max(0, Math.min(remaining, marker.length()));
        return new Value(marker.substring(0, length), true, length);
    }

    private boolean isCharacterType(int type, String typeName) {
        if (type == Types.CHAR || type == Types.VARCHAR || type == Types.LONGVARCHAR || type == Types.NCHAR
                || type == Types.NVARCHAR || type == Types.LONGNVARCHAR || type == Types.CLOB || type == Types.NCLOB
                || type == Types.SQLXML) return true;
        if (type != Types.OTHER || typeName == null) return false;
        return List.of("uuid", "json", "jsonb", "citext", "enum").contains(typeName.toLowerCase(Locale.ROOT));
    }

    private Map<TableKey, List<String>> loadPrimaryKeys(DatabaseMetaData metadata, AgenticSqlDatabase database,
                                                        List<OutputColumn> outputColumns,
                                                        List<AgenticSqlTableReference> sourceTables) throws SQLException {
        LinkedHashSet<TableKey> tables = new LinkedHashSet<>();
        for (AgenticSqlTableReference table : sourceTables) {
            tables.add(new TableKey(table.catalog(), table.schema(), table.name()));
        }
        for (OutputColumn column : outputColumns) {
            if (column.table() != null && !column.table().isBlank()) {
                if (database == AgenticSqlDatabase.MYSQL) {
                    AgenticSqlTableReference source = sourceTables.stream()
                            .filter(table -> table.name().equalsIgnoreCase(column.table()))
                            .findFirst().orElse(null);
                    String catalog = source != null ? source.catalog() : normalize(column.catalog());
                    tables.add(new TableKey(catalog, null, column.table()));
                } else {
                    tables.add(new TableKey(normalize(column.catalog()), normalize(column.schema()), column.table()));
                }
            }
        }
        Map<TableKey, List<String>> result = new HashMap<>();
        for (TableKey table : tables) {
            TreeMap<Short, String> ordered = new TreeMap<>();
            try (ResultSet keys = metadata.getPrimaryKeys(table.catalog(),
                    database == AgenticSqlDatabase.MYSQL ? null : table.schema(), table.table())) {
                while (keys.next()) {
                    String name = keys.getString("COLUMN_NAME");
                    if (metadataPolicy.isColumnVisible(name)) ordered.put(keys.getShort("KEY_SEQ"), name);
                }
            }
            result.put(table, List.copyOf(ordered.values()));
        }
        return result;
    }

    private List<AgenticSqlSourceRow> sourceRows(int rowIndex, List<OutputColumn> columns,
                                                Map<Integer, Object> valuesByIndex,
                                                Map<TableKey, List<String>> primaryKeys) {
        List<AgenticSqlSourceRow> sources = new ArrayList<>();
        for (Map.Entry<TableKey, List<String>> entry : primaryKeys.entrySet()) {
            LinkedHashMap<String, String> keyValues = new LinkedHashMap<>();
            for (String key : entry.getValue()) {
                OutputColumn match = columns.stream().filter(column -> sameTable(entry.getKey(), column)
                        && column.physicalColumn().equalsIgnoreCase(key)).findFirst().orElse(null);
                if (match == null || valuesByIndex.get(match.index()) == null) {
                    keyValues.clear();
                    break;
                }
                String value = valuesByIndex.get(match.index()).toString();
                if (value.length() > 256 || value.startsWith("[")) {
                    keyValues.clear();
                    break;
                }
                keyValues.put(key, value);
            }
            if (entry.getValue().isEmpty() || keyValues.size() != entry.getValue().size()) continue;
            String schema = entry.getKey().schema() == null ? entry.getKey().catalog() : entry.getKey().schema();
            sources.add(new AgenticSqlSourceRow(rowIndex, schema, entry.getKey().table(), keyValues));
        }
        return sources;
    }

    private boolean sameTable(TableKey table, OutputColumn column) {
        return table.table().equalsIgnoreCase(column.table())
                && equalsNullable(table.schema(), column.schema())
                && equalsNullable(table.catalog(), column.catalog());
    }

    private boolean equalsNullable(String left, String right) {
        if (left == null || left.isBlank()) return right == null || right.isBlank();
        return right != null && left.equalsIgnoreCase(right);
    }

    private String uniqueLabel(String label, LinkedHashSet<String> labels) {
        String base = label == null || label.isBlank() ? "column" : label;
        String unique = base;
        int suffix = 2;
        while (!labels.add(unique)) unique = base + "_" + suffix++;
        return unique;
    }

    private String safeColumnLabel(ResultSetMetaData metadata, int index) throws SQLException {
        String label = metadata.getColumnLabel(index);
        return label == null || label.isBlank() ? metadata.getColumnName(index) : label;
    }

    private String safeColumnName(ResultSetMetaData metadata, int index) {
        try {
            return metadata.getColumnName(index);
        } catch (SQLException exception) {
            return "";
        }
    }

    private String safeTableName(ResultSetMetaData metadata, int index) {
        try {
            return metadata.getTableName(index);
        } catch (SQLException exception) {
            return null;
        }
    }

    private String safeSchemaName(ResultSetMetaData metadata, int index) {
        try {
            return metadata.getSchemaName(index);
        } catch (SQLException exception) {
            return null;
        }
    }

    private String safeCatalogName(ResultSetMetaData metadata, int index) {
        try {
            return metadata.getCatalogName(index);
        } catch (SQLException exception) {
            return null;
        }
    }

    private String safeTypeName(ResultSetMetaData metadata, int index) {
        try {
            return metadata.getColumnTypeName(index);
        } catch (SQLException exception) {
            return null;
        }
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private int timeoutSeconds(Duration duration) {
        return Math.toIntExact(Math.max(1, (duration.toMillis() + 999) / 1000));
    }

    private boolean isTimeout(SQLException exception) {
        for (SQLException current = exception; current != null; current = current.getNextException()) {
            String state = current.getSQLState();
            if ("57014".equals(state) || "HYT00".equals(state) || "HYT01".equals(state)
                    || current.getErrorCode() == 3024 || current.getErrorCode() == 1317) return true;
        }
        return false;
    }

    private boolean isConnectionFailure(SQLException exception) {
        for (SQLException current = exception; current != null; current = current.getNextException()) {
            String state = current.getSQLState();
            if (state != null && state.startsWith("08")) return true;
        }
        return false;
    }

    private long elapsedMs(long started) {
        return Math.max(0, (System.nanoTime() - started) / 1_000_000);
    }

    private record QueryRows(List<String> columns, List<Map<String, Object>> rows,
                             List<AgenticSqlSourceRow> sourceRows,
                             Map<String, java.util.Set<String>> projectionSources, boolean truncated) {
    }

    private record OutputColumn(int index, String label, String physicalColumn, String schema,
                                String table, String catalog, int jdbcType, String typeName,
                                java.util.Set<String> sourceColumns) {
    }

    private record TableKey(String catalog, String schema, String table) {
    }

    private record Value(Object value, boolean truncated, int characters) {
    }
}
