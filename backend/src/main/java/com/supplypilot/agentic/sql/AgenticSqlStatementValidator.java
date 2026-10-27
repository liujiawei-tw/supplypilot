package com.supplypilot.agentic.sql;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;

import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.JdbcNamedParameter;
import net.sf.jsqlparser.expression.JdbcParameter;
import net.sf.jsqlparser.expression.NextValExpression;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
import net.sf.jsqlparser.expression.UserVariable;
import net.sf.jsqlparser.expression.VariableAssignment;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.ParenthesedFromItem;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectVisitorAdapter;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.stereotype.Component;

/**
 * Parses an untrusted SQL proposal, accepts only read-only SELECT trees, checks every SQL
 * function against a deterministic built-in allowlist, and adds a dialect-compatible outer LIMIT.
 */
@Component
public final class AgenticSqlStatementValidator {
    private static final int MAX_SQL_LENGTH = 16_000;

    /** Explicitly deterministic scalar and aggregate functions shared by MySQL and PostgreSQL. */
    private static final Set<String> ALLOWED_FUNCTIONS = Set.of(
            "ABS", "AVG", "CEIL", "CEILING", "CHAR_LENGTH", "CHARACTER_LENGTH", "COALESCE", "CONCAT",
            "CONCAT_WS", "COUNT", "DATE_PART", "DATE_TRUNC", "DATEDIFF", "DAY", "EXTRACT", "FLOOR",
            "GREATEST", "GROUP_CONCAT", "HOUR", "IF", "IFNULL", "JSON_EXTRACT", "JSON_LENGTH",
            "JSON_QUERY", "JSON_UNQUOTE", "JSON_VALUE", "LEAST", "LEFT", "LENGTH", "LOWER", "LTRIM",
            "MAX", "MIN", "MOD", "MONTH", "NULLIF", "NVL", "POSITION", "POWER", "QUARTER", "REPLACE",
            "REVERSE", "RIGHT", "ROUND", "RTRIM", "SUBSTR", "SUBSTRING", "SUM", "TRIM", "TRUNC",
            "UPPER", "YEAR"
    );
    private final AgenticSqlMetadataPolicy metadataPolicy;

    public AgenticSqlStatementValidator() {
        this(new AgenticSqlMetadataPolicy());
    }

    AgenticSqlStatementValidator(AgenticSqlMetadataPolicy metadataPolicy) {
        this.metadataPolicy = metadataPolicy;
    }

    public AgenticSqlValidatedStatement validate(AgenticSqlDatabase database, String sql, int maxRows) {
        if (database == null || sql == null || sql.isBlank() || sql.length() > MAX_SQL_LENGTH || maxRows < 1) {
            throw new AgenticSqlException(AgenticSqlException.Code.INVALID_SQL);
        }

        Statement parsed;
        try {
            Statements statements = CCJSqlParserUtil.parseStatements(sql);
            if (statements.getStatements().size() != 1) {
                throw new AgenticSqlException(AgenticSqlException.Code.UNSAFE_SQL);
            }
            parsed = statements.getStatements().getFirst();
        } catch (AgenticSqlException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AgenticSqlException(AgenticSqlException.Code.INVALID_SQL);
        }

        if (!(parsed instanceof Select select)) {
            throw new AgenticSqlException(AgenticSqlException.Code.UNSAFE_SQL);
        }

        Set<String> sensitiveColumns = validateSelectTree(select);
        AgenticSqlProjectionLineage.Result projectionLineage = AgenticSqlProjectionLineage.analyze(select);
        applyOuterLimit(select, Math.addExact(maxRows, 1));
        List<AgenticSqlTableReference> tables = collectTables(database, parsed);
        if (tables.stream().anyMatch(reference ->
                !metadataPolicy.isTableVisible(database, reference.schema(), reference.name()))) {
            throw new AgenticSqlException(AgenticSqlException.Code.UNSAFE_SQL);
        }
        Map<Integer, List<String>> sensitiveProjectionColumns = sensitiveProjectionColumns(select);
        Set<String> projectedSensitive = sensitiveProjectionColumns.values().stream().flatMap(List::stream)
                .map(AgenticSqlStatementValidator::normalizeName).collect(java.util.stream.Collectors.toSet());
        boolean maskAllOutput = sensitiveColumns.stream().map(AgenticSqlStatementValidator::normalizeName)
                .anyMatch(column -> !projectedSensitive.contains(column));
        // JDBC expands wildcard projections, so AST item positions no longer identify the output
        // columns that follow a star. If the same query also references a sensitive identifier,
        // fail closed for every returned value rather than risk masking the wrong alias.
        if (!sensitiveColumns.isEmpty() && containsWildcardProjection(select)) maskAllOutput = true;
        // The current mapper cannot preserve sensitive-field positions through arbitrary derived
        // projections/reordering. Reject only those ambiguous sensitive queries; ordinary derived
        // queries stay usable and are not returned as successful all-masked business data.
        if (containsDerivedFromSubquery(select)
                && (!sensitiveColumns.isEmpty() || containsSensitiveColumnInNestedSource(select))) {
            throw new AgenticSqlException(AgenticSqlException.Code.UNSAFE_SQL);
        }
        String documentSourceTable = null;
        if (database == AgenticSqlDatabase.MYSQL && hasTable(tables, "document_chunks")) {
            documentSourceTable = "document_chunks";
        } else if (database == AgenticSqlDatabase.POSTGRESQL && hasTable(tables, "semantic_chunks")) {
            documentSourceTable = "semantic_chunks";
        }
        boolean documentContentReference = documentSourceTable != null
                && (referencesColumnInSelect(select, "content") || containsWildcardProjection(select)
                // Some JSQLParser expression visitors do not descend into every predicate subquery.
                // The rendered, parsed tree is a conservative fallback so aliases or nested SQL
                // cannot hide a reference to the document body from the outbound policy.
                || containsIdentifier(select.toString(), "content"));
        if (documentContentReference && (projectionLineage.unsafeDocumentContentUse()
                || projectionLineage.containsNestedSelectExpression())) {
            throw new AgenticSqlException(AgenticSqlException.Code.DOCUMENT_EGRESS_REJECTED);
        }
        String documentIdColumnLabel = null;
        String documentVersionColumnLabel = null;
        if (documentContentReference) {
            if (tables.stream().filter(table -> Set.of("document_chunks", "semantic_chunks")
                    .contains(normalizeName(table.name()))).count() != 1) {
                throw new AgenticSqlException(AgenticSqlException.Code.UNSAFE_SQL);
            }
            documentIdColumnLabel = directDocumentColumnLabel(select, documentSourceTable, "document_id");
            documentVersionColumnLabel = directDocumentColumnLabel(select, documentSourceTable, "document_version");
        }
        return new AgenticSqlValidatedStatement(database, select.toString(), tables,
                sensitiveProjectionColumns, sensitiveColumns, maskAllOutput,
                documentContentReference, documentSourceTable, documentIdColumnLabel, documentVersionColumnLabel,
                projectionLineage.projectionSources(), projectionLineage.ambiguous());
    }

    private boolean hasTable(List<AgenticSqlTableReference> tables, String name) {
        return tables.stream().anyMatch(table -> normalizeName(table.name()).equals(name));
    }

    private boolean containsIdentifier(String sql, String identifier) {
        return java.util.regex.Pattern.compile("(?i)(?<![\\p{L}\\p{N}_])"
                        + java.util.regex.Pattern.quote(identifier) + "(?![\\p{L}\\p{N}_])")
                .matcher(sql).find();
    }

    private boolean referencesColumnInSelect(Select select, String name) {
        if (select instanceof PlainSelect plainSelect) {
            if (plainSelect.getSelectItems().stream()
                    .anyMatch(item -> referencesColumnInExpression(item.getExpression(), name))) return true;
            if (referencesColumnInExpression(plainSelect.getPreWhere(), name)
                    || referencesColumnInExpression(plainSelect.getWhere(), name)
                    || referencesColumnInExpression(plainSelect.getHaving(), name)) return true;
            if (plainSelect.getGroupBy() != null && plainSelect.getGroupBy().getGroupByExpressions() != null
                    && plainSelect.getGroupBy().getGroupByExpressions().stream()
                    .anyMatch(expression -> referencesColumnInExpression(expression, name))) return true;
            if (plainSelect.getOrderByElements() != null && plainSelect.getOrderByElements().stream()
                    .map(element -> element.getExpression()).filter(java.util.Objects::nonNull)
                    .anyMatch(expression -> referencesColumnInExpression(expression, name))) return true;
            if (plainSelect.getJoins() != null && plainSelect.getJoins().stream()
                    .filter(join -> join.getOnExpressions() != null)
                    .flatMap(join -> join.getOnExpressions().stream())
                    .anyMatch(expression -> referencesColumnInExpression(expression, name))) return true;
        }
        ColumnReferenceVisitor selectVisitor = new ColumnReferenceVisitor(name);
        select.accept(new SelectVisitorAdapter<>(selectVisitor), null);
        if (selectVisitor.found) return true;
        if (select.getWithItemsList() != null && select.getWithItemsList().stream()
                .map(WithItem::getParenthesedStatement)
                .filter(ParenthesedSelect.class::isInstance)
                .map(ParenthesedSelect.class::cast)
                .anyMatch(body -> referencesColumnInSelect(body.getSelect(), name))) return true;
        if (select instanceof PlainSelect plainSelect) {
            if (referencesColumnInSelect(plainSelect.getFromItem(), name)) return true;
            return plainSelect.getJoins() != null && plainSelect.getJoins().stream()
                    .anyMatch(join -> referencesColumnInSelect(join.getRightItem(), name));
        }
        if (select instanceof SetOperationList setOperationList) {
            return setOperationList.getSelects().stream().anyMatch(branch -> referencesColumnInSelect(branch, name));
        }
        if (select instanceof ParenthesedSelect parenthesedSelect) {
            return referencesColumnInSelect(parenthesedSelect.getSelect(), name);
        }
        return false;
    }

    private boolean referencesColumnInExpression(net.sf.jsqlparser.expression.Expression expression, String name) {
        if (expression == null) return false;
        ColumnReferenceVisitor visitor = new ColumnReferenceVisitor(name);
        expression.accept(visitor, null);
        return visitor.found;
    }

    private boolean referencesColumnInSelect(FromItem fromItem, String name) {
        if (fromItem instanceof ParenthesedSelect parenthesedSelect) {
            return referencesColumnInSelect(parenthesedSelect.getSelect(), name);
        }
        if (fromItem instanceof ParenthesedFromItem parenthesed) {
            if (referencesColumnInSelect(parenthesed.getFromItem(), name)) return true;
            return parenthesed.getJoins() != null && parenthesed.getJoins().stream()
                    .anyMatch(join -> referencesColumnInSelect(join.getRightItem(), name));
        }
        return false;
    }

    /** Finds sensitive fields projected inside a derived source before aliases hide their lineage. */
    private boolean containsSensitiveColumnInNestedSource(Select select) {
        if (select instanceof PlainSelect plainSelect) {
            if (containsSensitiveColumnInDerivedItem(plainSelect.getFromItem())) return true;
            if (plainSelect.getJoins() != null && plainSelect.getJoins().stream()
                    .anyMatch(join -> containsSensitiveColumnInDerivedItem(join.getRightItem()))) return true;
        } else if (select instanceof SetOperationList setOperationList
                && setOperationList.getSelects().stream().anyMatch(this::containsSensitiveColumnInNestedSource)) {
            return true;
        } else if (select instanceof ParenthesedSelect parenthesedSelect
                && containsSensitiveColumnInNestedSource(parenthesedSelect.getSelect())) {
            return true;
        }
        return select.getWithItemsList() != null && select.getWithItemsList().stream()
                .map(WithItem::getParenthesedStatement)
                .filter(ParenthesedSelect.class::isInstance)
                .map(ParenthesedSelect.class::cast)
                .anyMatch(body -> containsSensitiveColumnInNestedSource(body.getSelect())
                        || containsSensitiveProjection(body.getSelect()));
    }

    private boolean containsSensitiveColumnInDerivedItem(FromItem fromItem) {
        if (fromItem instanceof ParenthesedSelect parenthesedSelect) {
            Select nested = parenthesedSelect.getSelect();
            return containsSensitiveProjection(nested) || containsSensitiveColumnInNestedSource(nested);
        }
        if (fromItem instanceof ParenthesedFromItem parenthesed) {
            if (containsSensitiveColumnInDerivedItem(parenthesed.getFromItem())) return true;
            return parenthesed.getJoins() != null && parenthesed.getJoins().stream()
                    .anyMatch(join -> containsSensitiveColumnInDerivedItem(join.getRightItem()));
        }
        return false;
    }

    private boolean containsSensitiveProjection(Select select) {
        if (select instanceof PlainSelect plainSelect) {
            if (plainSelect.getSelectItems().stream()
                    .anyMatch(item -> !sensitiveColumnsIn(item.getExpression()).isEmpty())) return true;
        } else if (select instanceof SetOperationList setOperationList) {
            if (setOperationList.getSelects().stream().anyMatch(this::containsSensitiveProjection)) return true;
        } else if (select instanceof ParenthesedSelect parenthesedSelect) {
            if (containsSensitiveProjection(parenthesedSelect.getSelect())) return true;
        }
        if (select.getWithItemsList() != null && select.getWithItemsList().stream()
                .map(WithItem::getParenthesedStatement)
                .filter(ParenthesedSelect.class::isInstance)
                .map(ParenthesedSelect.class::cast)
                .anyMatch(body -> containsSensitiveProjection(body.getSelect()))) return true;
        if (select instanceof PlainSelect plainSelect) {
            if (containsSensitiveColumnInDerivedItem(plainSelect.getFromItem())) return true;
            return plainSelect.getJoins() != null && plainSelect.getJoins().stream()
                    .anyMatch(join -> containsSensitiveColumnInDerivedItem(join.getRightItem()));
        }
        return false;
    }

    private String directDocumentColumnLabel(Select select, String documentTable, String columnName) {
        if (!(select instanceof PlainSelect plainSelect)) return null;
        List<Table> directTables = new ArrayList<>();
        collectDirectTables(plainSelect.getFromItem(), directTables);
        if (plainSelect.getJoins() != null) {
            for (Join join : plainSelect.getJoins()) collectDirectTables(join.getRightItem(), directTables);
        }
        List<Table> matchingTables = directTables.stream()
                .filter(table -> normalizeName(table.getName()).equals(documentTable)).toList();
        if (matchingTables.size() != 1) return null;
        Table source = matchingTables.getFirst();
        String sourceAlias = source.getAlias() == null ? source.getName() : source.getAlias().getName();
        for (net.sf.jsqlparser.statement.select.SelectItem<?> item : plainSelect.getSelectItems()) {
            if (!(item.getExpression() instanceof Column column)
                    || !normalizeName(column.getColumnName()).equals(columnName)) continue;
            String qualifier = column.getTable() == null ? null : column.getTable().getName();
            if (qualifier != null && !normalizeName(qualifier).equals(normalizeName(sourceAlias))
                    && !normalizeName(qualifier).equals(documentTable)) continue;
            if (qualifier == null && directTables.size() != 1) continue;
            return item.getAlias() == null ? column.getColumnName() : item.getAlias().getName();
        }
        return null;
    }

    private void collectDirectTables(FromItem item, List<Table> tables) {
        if (item instanceof Table table) {
            tables.add(table);
        } else if (item instanceof ParenthesedFromItem parenthesed) {
            collectDirectTables(parenthesed.getFromItem(), tables);
            if (parenthesed.getJoins() != null) {
                for (Join join : parenthesed.getJoins()) collectDirectTables(join.getRightItem(), tables);
            }
        }
    }

    private boolean containsDerivedFromSubquery(Select select) {
        if (select.getWithItemsList() != null) {
            for (WithItem<?> item : select.getWithItemsList()) {
                if (item.getParenthesedStatement() instanceof ParenthesedSelect body
                        && containsDerivedFromSubquery(body.getSelect())) return true;
            }
        }
        if (select instanceof PlainSelect plainSelect) {
            if (isDerivedFromItem(plainSelect.getFromItem())) return true;
            if (plainSelect.getJoins() != null) {
                for (Join join : plainSelect.getJoins()) {
                    if (isDerivedFromItem(join.getRightItem())) return true;
                }
            }
            return false;
        }
        if (select instanceof SetOperationList setOperationList) {
            return setOperationList.getSelects().stream().anyMatch(this::containsDerivedFromSubquery);
        }
        if (select instanceof ParenthesedSelect parenthesedSelect) {
            return containsDerivedFromSubquery(parenthesedSelect.getSelect());
        }
        return false;
    }

    private boolean isDerivedFromItem(FromItem fromItem) {
        if (fromItem instanceof ParenthesedSelect) return true;
        if (fromItem instanceof ParenthesedFromItem parenthesed) {
            if (isDerivedFromItem(parenthesed.getFromItem())) return true;
            return parenthesed.getJoins() != null && parenthesed.getJoins().stream()
                    .anyMatch(join -> isDerivedFromItem(join.getRightItem()));
        }
        return false;
    }

    private boolean containsWildcardProjection(Select select) {
        if (select instanceof PlainSelect plainSelect) {
            return plainSelect.getSelectItems().stream().anyMatch(item ->
                    item.getExpression() instanceof AllColumns || item.getExpression() instanceof AllTableColumns);
        }
        if (select instanceof SetOperationList setOperationList) {
            return setOperationList.getSelects().stream().anyMatch(this::containsWildcardProjection);
        }
        if (select instanceof ParenthesedSelect parenthesedSelect) {
            return containsWildcardProjection(parenthesedSelect.getSelect());
        }
        return false;
    }

    private Set<String> validateSelectTree(Select select) {
        SafeSelectVisitor selectVisitor = new SafeSelectVisitor();
        select.accept(selectVisitor, null);
        return inspectFunctions(select);
    }

    private Set<String> inspectFunctions(Select select) {
        FunctionValidator functionValidator = new FunctionValidator(metadataPolicy);
        SelectVisitorAdapter<Void> selectVisitor = new FunctionSelectVisitor(functionValidator);
        functionValidator.setSelectVisitor(selectVisitor);
        select.accept(selectVisitor, null);
        return Set.copyOf(functionValidator.sensitiveColumns);
    }

    private Map<Integer, List<String>> sensitiveProjectionColumns(Select select) {
        LinkedHashMap<Integer, LinkedHashSet<String>> collected = new LinkedHashMap<>();
        collectProjectionColumns(select, collected);
        LinkedHashMap<Integer, List<String>> result = new LinkedHashMap<>();
        collected.forEach((index, columns) -> {
            if (!columns.isEmpty()) result.put(index, List.copyOf(columns));
        });
        return Map.copyOf(result);
    }

    private void collectProjectionColumns(Select select, Map<Integer, LinkedHashSet<String>> collected) {
        if (select instanceof PlainSelect plainSelect) {
            List<net.sf.jsqlparser.statement.select.SelectItem<?>> items = plainSelect.getSelectItems();
            for (int index = 0; index < items.size(); index++) {
                Set<String> sensitive = sensitiveColumnsIn(items.get(index).getExpression());
                if (!sensitive.isEmpty()) collected.computeIfAbsent(index + 1, ignored -> new LinkedHashSet<>())
                        .addAll(sensitive);
            }
            return;
        }
        if (select instanceof SetOperationList setOperationList) {
            for (Select branch : setOperationList.getSelects()) collectProjectionColumns(branch, collected);
            return;
        }
        if (select instanceof ParenthesedSelect parenthesedSelect) {
            collectProjectionColumns(parenthesedSelect.getSelect(), collected);
        }
    }

    private Set<String> sensitiveColumnsIn(net.sf.jsqlparser.expression.Expression expression) {
        if (expression == null) return Set.of();
        SensitiveExpressionVisitor expressionVisitor = new SensitiveExpressionVisitor(metadataPolicy);
        SelectVisitorAdapter<Void> selectVisitor = new SelectVisitorAdapter<>(expressionVisitor);
        expressionVisitor.setSelectVisitor(selectVisitor);
        expression.accept(expressionVisitor, null);
        return Set.copyOf(expressionVisitor.columns);
    }

    private void applyOuterLimit(Select select, long cap) {
        if (select.getFetch() != null) {
            if (select.getFetch().getFetchJdbcParameter() != null || !select.getFetch().getFetchParameters().isEmpty()) {
                throw new AgenticSqlException(AgenticSqlException.Code.UNSAFE_SQL);
            }
            cap = Math.min(cap, select.getFetch().getRowCount());
            select.setFetch(null);
        }

        Limit limit = select.getLimit();
        if (limit == null) {
            limit = new Limit();
            select.setLimit(limit);
        } else if (limit.getRowCount() instanceof LongValue longValue) {
            cap = Math.min(cap, Math.max(0, longValue.getValue()));
        } else if (limit.getRowCount() != null && !limit.isLimitAll() && !limit.isLimitNull()) {
            throw new AgenticSqlException(AgenticSqlException.Code.UNSAFE_SQL);
        }
        limit.setLimitAll(false);
        limit.setLimitNull(false);
        limit.setRowCount(new LongValue(cap));
    }

    private List<AgenticSqlTableReference> collectTables(AgenticSqlDatabase database, Statement statement) {
        TableCollector collector = new TableCollector();
        collector.getTables(statement);
        Set<String> cteNames = collector.cteNames.stream().map(AgenticSqlStatementValidator::normalizeName)
                .collect(java.util.stream.Collectors.toSet());
        if (cteNames.stream().anyMatch(name -> !metadataPolicy.isTableVisible(database, null, name))) {
            // A hidden physical table name must not be hidden behind a same-named CTE.
            throw new AgenticSqlException(AgenticSqlException.Code.UNSAFE_SQL);
        }
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<AgenticSqlTableReference> result = new ArrayList<>();
        for (AgenticSqlTableReference reference : collector.tables) {
            if (reference.schema() == null && cteNames.contains(normalizeName(reference.name()))) continue;
            String key = normalizeName(reference.qualifiedName());
            if (seen.add(key)) result.add(reference);
        }
        return List.copyOf(result);
    }

    private static String normalizeName(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static void validateWithItems(Select select, SelectVisitorAdapter<Void> visitor) {
        if (select.getWithItemsList() == null) return;
        for (WithItem<?> item : select.getWithItemsList()) {
            if (!(item.getParenthesedStatement() instanceof ParenthesedSelect body)) {
                throw new AgenticSqlException(AgenticSqlException.Code.UNSAFE_SQL);
            }
            body.accept(visitor, null);
        }
    }

    private static void rejectUnsafeSelectFeatures(Select select) {
        if (select.getForUpdate() != null || select.getForUpdateTables() != null && !select.getForUpdateTables().isEmpty()
                || select.getForUpdateTable() != null || select.getForMode() != null || select.isNoWait()
                || select.isSkipLocked() || select.getWait() != null || select.getIsolation() != null
                || select.getMySqlProcedureAnalyse() != null) {
            throw new AgenticSqlException(AgenticSqlException.Code.UNSAFE_SQL);
        }
        if (select instanceof PlainSelect plainSelect
                && (plainSelect.getIntoTables() != null && !plainSelect.getIntoTables().isEmpty()
                || plainSelect.getIntoTempTable() != null || plainSelect.getMySqlSelectIntoClause() != null)) {
            throw new AgenticSqlException(AgenticSqlException.Code.UNSAFE_SQL);
        }
    }

    private static void validateFromItem(FromItem fromItem) {
        if (fromItem == null || fromItem instanceof Table) return;
        if (fromItem instanceof ParenthesedSelect) return;
        if (fromItem instanceof ParenthesedFromItem parenthesed) {
            validateFromItem(parenthesed.getFromItem());
            if (parenthesed.getJoins() != null) {
                for (Join join : parenthesed.getJoins()) validateFromItem(join.getRightItem());
            }
            return;
        }
        throw new AgenticSqlException(AgenticSqlException.Code.UNSAFE_SQL);
    }

    private static final class SafeSelectVisitor extends SelectVisitorAdapter<Void> {
        @Override
        public <S> Void visit(PlainSelect select, S context) {
            rejectUnsafeSelectFeatures(select);
            validateFromItem(select.getFromItem());
            if (select.getJoins() != null) {
                for (Join join : select.getJoins()) validateFromItem(join.getRightItem());
            }
            validateWithItems(select, this);
            return super.visit(select, context);
        }

        @Override
        public <S> Void visit(ParenthesedSelect select, S context) {
            rejectUnsafeSelectFeatures(select);
            validateWithItems(select, this);
            return super.visit(select, context);
        }

        @Override
        public <S> Void visit(SetOperationList select, S context) {
            rejectUnsafeSelectFeatures(select);
            validateWithItems(select, this);
            return super.visit(select, context);
        }

        @Override
        public <S> Void visit(net.sf.jsqlparser.statement.select.Values values, S context) {
            throw new AgenticSqlException(AgenticSqlException.Code.UNSAFE_SQL);
        }

        @Override
        public <S> Void visit(net.sf.jsqlparser.statement.select.PivotQuery query, S context) {
            throw new AgenticSqlException(AgenticSqlException.Code.UNSAFE_SQL);
        }

        @Override
        public <S> Void visit(net.sf.jsqlparser.statement.piped.FromQuery query, S context) {
            throw new AgenticSqlException(AgenticSqlException.Code.UNSAFE_SQL);
        }
    }

    private static final class FunctionValidator extends ExpressionVisitorAdapter<Void> {
        private final AgenticSqlMetadataPolicy metadataPolicy;
        private final LinkedHashSet<String> sensitiveColumns = new LinkedHashSet<>();

        private FunctionValidator(AgenticSqlMetadataPolicy metadataPolicy) {
            this.metadataPolicy = metadataPolicy;
        }

        @Override
        public <S> Void visit(Function function, S context) {
            String name = function.getName();
            if (name == null || function.getMultipartName() != null && function.getMultipartName().size() > 1
                    || !ALLOWED_FUNCTIONS.contains(name.toUpperCase(Locale.ROOT))
                    || function.getAttribute() != null || function.getKeep() != null
                    || function.getKeywordArguments() != null && !function.getKeywordArguments().isEmpty()) {
                throw new AgenticSqlException(AgenticSqlException.Code.UNSAFE_SQL);
            }
            return super.visit(function, context);
        }

        @Override
        public <S> Void visit(ExistsExpression exists, S context) {
            if (exists.getRightExpression() != null) exists.getRightExpression().accept(this, context);
            return null;
        }

        @Override
        public <S> Void visit(ParenthesedSelect select, S context) {
            select.getSelect().accept(new SelectVisitorAdapter<>(this), context);
            return null;
        }

        @Override
        public <S> Void visit(JdbcParameter parameter, S context) {
            throw new AgenticSqlException(AgenticSqlException.Code.UNSAFE_SQL);
        }

        @Override
        public <S> Void visit(JdbcNamedParameter parameter, S context) {
            throw new AgenticSqlException(AgenticSqlException.Code.UNSAFE_SQL);
        }

        @Override
        public <S> Void visit(NextValExpression expression, S context) {
            throw new AgenticSqlException(AgenticSqlException.Code.UNSAFE_SQL);
        }

        @Override
        public <S> Void visit(VariableAssignment assignment, S context) {
            throw new AgenticSqlException(AgenticSqlException.Code.UNSAFE_SQL);
        }

        @Override
        public <S> Void visit(UserVariable variable, S context) {
            throw new AgenticSqlException(AgenticSqlException.Code.UNSAFE_SQL);
        }

        @Override
        public <S> Void visit(Column column, S context) {
            if (!metadataPolicy.isColumnVisible(column.getColumnName())) {
                throw new AgenticSqlException(AgenticSqlException.Code.UNSAFE_SQL);
            }
            if (metadataPolicy.isSensitiveColumn(column.getColumnName())) {
                sensitiveColumns.add(column.getColumnName());
            }
            return super.visit(column, context);
        }
    }

    private static final class SensitiveExpressionVisitor extends ExpressionVisitorAdapter<Void> {
        private final AgenticSqlMetadataPolicy metadataPolicy;
        private final LinkedHashSet<String> columns = new LinkedHashSet<>();

        private SensitiveExpressionVisitor(AgenticSqlMetadataPolicy metadataPolicy) {
            this.metadataPolicy = metadataPolicy;
        }

        @Override
        public <S> Void visit(Column column, S context) {
            if (metadataPolicy.isSensitiveColumn(column.getColumnName())) columns.add(column.getColumnName());
            return super.visit(column, context);
        }

        @Override
        public <S> Void visit(ExistsExpression exists, S context) {
            if (exists.getRightExpression() != null) exists.getRightExpression().accept(this, context);
            return null;
        }

        @Override
        public <S> Void visit(ParenthesedSelect select, S context) {
            select.getSelect().accept(new SelectVisitorAdapter<>(this), context);
            return null;
        }
    }

    private static final class ColumnReferenceVisitor extends ExpressionVisitorAdapter<Void> {
        private final String expectedName;
        private boolean found;

        private ColumnReferenceVisitor(String expectedName) {
            this.expectedName = normalizeName(expectedName);
        }

        @Override
        public <S> Void visit(Column column, S context) {
            if (normalizeName(column.getColumnName()).equals(expectedName)) found = true;
            return super.visit(column, context);
        }

        @Override
        public <S> Void visit(ExistsExpression exists, S context) {
            if (exists.getRightExpression() != null) exists.getRightExpression().accept(this, context);
            return null;
        }

        @Override
        public <S> Void visit(ParenthesedSelect select, S context) {
            select.getSelect().accept(new SelectVisitorAdapter<>(this), context);
            return null;
        }
    }

    private static final class FunctionSelectVisitor extends SelectVisitorAdapter<Void> {
        private FunctionSelectVisitor(FunctionValidator visitor) {
            super(visitor);
        }

        @Override
        public <S> Void visit(PlainSelect select, S context) {
            validateWithItems(select, this);
            return super.visit(select, context);
        }

        @Override
        public <S> Void visit(ParenthesedSelect select, S context) {
            validateWithItems(select, this);
            return super.visit(select, context);
        }

        @Override
        public <S> Void visit(SetOperationList select, S context) {
            validateWithItems(select, this);
            return super.visit(select, context);
        }
    }

    private static final class TableCollector extends TablesNamesFinder<Void> {
        private final List<AgenticSqlTableReference> tables = new ArrayList<>();
        private final Set<String> cteNames = new LinkedHashSet<>();

        @Override
        public <S> Void visit(Table table, S context) {
            String alias = table.getAlias() == null ? null : table.getAlias().getName();
            tables.add(new AgenticSqlTableReference(
                    table.getCatalogName(), table.getSchemaName(), table.getName(), alias));
            return super.visit(table, context);
        }

        @Override
        public <S> Void visit(WithItem<?> item, S context) {
            if (item.getAliasName() != null) cteNames.add(item.getAliasName());
            return super.visit(item, context);
        }
    }
}
