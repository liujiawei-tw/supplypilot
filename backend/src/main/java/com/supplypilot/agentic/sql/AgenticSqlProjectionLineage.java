package com.supplypilot.agentic.sql;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedFromItem;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;

/** Resolves result aliases to database columns without treating a FROM clause as proof for constants. */
final class AgenticSqlProjectionLineage {
    private AgenticSqlProjectionLineage() { }

    static Result analyze(Select select) {
        QueryProjection query = new Analyzer().analyzeQuery(select, Map.of());
        LinkedHashMap<Integer, Set<String>> sources = new LinkedHashMap<>();
        for (int index = 0; index < query.projections().size(); index++) {
            sources.put(index + 1, query.projections().get(index).sources());
        }
        return new Result(sources, query.ambiguous());
    }

    record Result(Map<Integer, Set<String>> projectionSources, boolean ambiguous) {
        Result {
            projectionSources = projectionSources.entrySet().stream().collect(
                    java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey,
                            entry -> Set.copyOf(entry.getValue())));
        }
    }

    private static final class Analyzer {
        private QueryProjection analyzeQuery(Select select, Map<String, Relation> inheritedCtes) {
            Map<String, Relation> ctes = new LinkedHashMap<>(inheritedCtes);
            if (select.getWithItemsList() != null) {
                for (WithItem<?> item : select.getWithItemsList()) {
                    String name = normalize(item.getAliasName());
                    if (name.isEmpty() || !(item.getParenthesedStatement() instanceof ParenthesedSelect body)) continue;
                    QueryProjection query = item.isRecursive()
                            ? QueryProjection.unsupported()
                            : analyzeQuery(body.getSelect(), ctes);
                    ctes.put(name, relationFromCte(item, query));
                }
            }

            if (select instanceof ParenthesedSelect parenthesed) {
                return analyzeQuery(parenthesed.getSelect(), ctes);
            }
            if (select instanceof SetOperationList setOperation) {
                List<QueryProjection> branches = setOperation.getSelects().stream()
                        .map(branch -> analyzeQuery(branch, ctes)).toList();
                return combineBranches(branches);
            }
            if (!(select instanceof PlainSelect plain)) return QueryProjection.unsupported();

            List<Relation> scope = new ArrayList<>();
            addRelation(plain.getFromItem(), ctes, scope);
            if (plain.getJoins() != null) {
                for (Join join : plain.getJoins()) addRelation(join.getRightItem(), ctes, scope);
            }
            Set<String> rowSources = scope.stream().flatMap(relation -> relation.rowSources().stream())
                    .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));

            List<Projection> projections = new ArrayList<>();
            boolean wildcard = false;
            for (int index = 0; index < plain.getSelectItems().size(); index++) {
                SelectItem<?> item = plain.getSelectItems().get(index);
                if (item.getExpression() instanceof AllColumns || item.getExpression() instanceof AllTableColumns) {
                    wildcard = true;
                    projections.add(new Projection("*" + (index + 1), Set.of()));
                } else {
                    projections.add(new Projection(label(item), expressionSources(item.getExpression(), scope, rowSources)));
                }
            }
            return new QueryProjection(projections, rowSources, wildcard);
        }

        private QueryProjection combineBranches(List<QueryProjection> branches) {
            if (branches.isEmpty()) return QueryProjection.unsupported();
            int count = branches.stream().mapToInt(branch -> branch.projections().size()).min().orElse(0);
            List<Projection> combined = new ArrayList<>();
            for (int index = 1; index <= count; index++) {
                LinkedHashSet<String> allSources = new LinkedHashSet<>();
                boolean everyBranchBacked = true;
                for (QueryProjection branch : branches) {
                    Set<String> sources = branch.projections().get(index - 1).sources();
                    if (sources.isEmpty()) everyBranchBacked = false;
                    else allSources.addAll(sources);
                }
                combined.add(new Projection(branches.getFirst().projections().get(index - 1).label(),
                        everyBranchBacked ? Set.copyOf(allSources) : Set.of()));
            }
            Set<String> rowSources = branches.stream().flatMap(branch -> branch.rowSources().stream())
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            return new QueryProjection(combined, rowSources, branches.stream().anyMatch(QueryProjection::ambiguous));
        }

        private Relation relationFromCte(WithItem<?> item, QueryProjection query) {
            List<String> explicitNames = item.getWithItemList() == null ? List.of()
                    : item.getWithItemList().stream().map(this::label).toList();
            List<Projection> projections = new ArrayList<>(query.projections());
            if (!explicitNames.isEmpty()) {
                for (int index = 0; index < projections.size(); index++) {
                    Projection projection = projections.get(index);
                    String name = index < explicitNames.size() ? explicitNames.get(index) : projection.label();
                    projections.set(index, new Projection(name, projection.sources()));
                }
            }
            return Relation.derived(item.getAliasName(), item.getAliasName(), projections, query.rowSources());
        }

        private void addRelation(FromItem item, Map<String, Relation> ctes, List<Relation> scope) {
            if (item instanceof Table table) {
                String physicalName = physicalName(table);
                Relation relation = table.getSchemaName() == null && table.getCatalogName() == null
                        ? ctes.get(normalize(table.getName())) : null;
                if (relation == null) relation = Relation.physical(physicalName);
                String alias = table.getAlias() == null ? null : table.getAlias().getName();
                scope.add(relation.withAlias(alias));
            } else if (item instanceof ParenthesedSelect derived) {
                QueryProjection query = analyzeQuery(derived.getSelect(), ctes);
                String alias = derived.getAlias() == null ? null : derived.getAlias().getName();
                scope.add(Relation.derived(alias, alias, query.projections(), query.rowSources()));
            } else if (item instanceof ParenthesedFromItem grouped) {
                addRelation(grouped.getFromItem(), ctes, scope);
                if (grouped.getJoins() != null) {
                    for (Join join : grouped.getJoins()) addRelation(join.getRightItem(), ctes, scope);
                }
            }
        }

        private String physicalName(Table table) {
            List<String> parts = new ArrayList<>();
            if (table.getCatalogName() != null && !table.getCatalogName().isBlank()) {
                parts.add(table.getCatalogName());
            }
            if (table.getSchemaName() != null && !table.getSchemaName().isBlank()) {
                parts.add(table.getSchemaName());
            }
            parts.add(table.getName());
            return String.join(".", parts);
        }

        private Set<String> expressionSources(net.sf.jsqlparser.expression.Expression expression,
                                             List<Relation> scope, Set<String> rowSources) {
            ExpressionLineageVisitor visitor = new ExpressionLineageVisitor();
            expression.accept(visitor, null);
            if (visitor.nestedSelect()) return Set.of();
            LinkedHashSet<String> sources = new LinkedHashSet<>();
            for (Column column : visitor.columns()) {
                Set<String> columnSources = resolve(column, scope);
                if (columnSources.isEmpty()) return Set.of();
                sources.addAll(columnSources);
            }
            if (!sources.isEmpty()) {
                for (Function function : visitor.functions()) {
                    String name = function.getName() == null ? "" : function.getName().toUpperCase(Locale.ROOT);
                    if (Set.of("COUNT", "SUM", "AVG", "MIN", "MAX").contains(name)) {
                        sources.add("@aggregate:" + name + ":" + String.join(",", sources));
                    }
                }
                return Set.copyOf(sources);
            }
            boolean rowCount = visitor.functions().stream().anyMatch(function ->
                    "COUNT".equalsIgnoreCase(function.getName()));
            if (rowCount && !rowSources.isEmpty()) {
                return Set.of("@aggregate:COUNT:" + String.join(",", rowSources));
            }
            return Set.of();
        }

        private Set<String> resolve(Column column, List<Relation> scope) {
            String name = column.getColumnName();
            String qualifier = column.getTable() == null ? null : column.getTable().getName();
            if (qualifier != null && !qualifier.isBlank()) {
                List<Relation> matching = scope.stream().filter(relation -> relation.matches(qualifier)).toList();
                if (matching.size() != 1) return Set.of();
                return matching.getFirst().resolve(name);
            }
            if (scope.size() != 1) return Set.of();
            return scope.getFirst().resolve(name);
        }

        private String label(SelectItem<?> item) {
            if (item.getAliasName() != null) return item.getAliasName();
            if (item.getExpression() instanceof Column column) return column.getColumnName();
            return item.getExpression() == null ? "" : item.getExpression().toString();
        }
    }

    private static final class ExpressionLineageVisitor extends ExpressionVisitorAdapter<Void> {
        private final List<Column> columns = new ArrayList<>();
        private final List<Function> functions = new ArrayList<>();
        private boolean nestedSelect;

        @Override
        public <S> Void visit(Column column, S context) {
            columns.add(column);
            return super.visit(column, context);
        }

        @Override
        public <S> Void visit(Function function, S context) {
            functions.add(function);
            return super.visit(function, context);
        }

        @Override
        public <S> Void visit(ParenthesedSelect select, S context) {
            nestedSelect = true;
            return null;
        }

        private List<Column> columns() { return columns; }
        private List<Function> functions() { return functions; }
        private boolean nestedSelect() { return nestedSelect; }
    }

    private record Projection(String label, Set<String> sources) {
        private Projection {
            label = normalize(label);
            sources = Set.copyOf(sources);
        }
    }

    private record QueryProjection(List<Projection> projections, Set<String> rowSources, boolean ambiguous) {
        private QueryProjection {
            projections = List.copyOf(projections);
            rowSources = Set.copyOf(rowSources);
        }

        private static QueryProjection unsupported() { return new QueryProjection(List.of(), Set.of(), true); }
    }

    private record Relation(String alias, String tableName, boolean physical,
                            Map<String, Set<String>> projectionSources, Set<String> rowSources) {
        private Relation {
            projectionSources = Map.copyOf(projectionSources);
            rowSources = Set.copyOf(rowSources);
        }

        private static Relation physical(String name) {
            String normalizedName = normalize(name);
            return new Relation(normalizedName, normalizedName, true, Map.of(), Set.of("@rows:" + normalizedName));
        }

        private static Relation derived(String alias, String tableName, List<Projection> projections,
                                        Set<String> rowSources) {
            Map<String, Set<String>> output = new LinkedHashMap<>();
            Set<String> duplicates = new LinkedHashSet<>();
            for (Projection projection : projections) {
                String label = normalize(projection.label());
                if (label.isEmpty() || output.putIfAbsent(label, projection.sources()) != null) duplicates.add(label);
            }
            duplicates.forEach(output::remove);
            return new Relation(normalize(alias), normalize(tableName), false, output, rowSources);
        }

        private Relation withAlias(String alias) {
            return new Relation(normalize(alias), tableName, physical, projectionSources, rowSources);
        }

        private boolean matches(String qualifier) {
            String candidate = normalize(qualifier);
            return candidate.equals(alias) || candidate.equals(tableName)
                    || physical && (candidate.equals(tableName.substring(tableName.lastIndexOf('.') + 1)));
        }

        private Set<String> resolve(String column) {
            if (physical) return Set.of(tableName + "." + normalize(column));
            return projectionSources.getOrDefault(normalize(column), Set.of());
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.replace("\"", "").replace("`", "").toLowerCase(Locale.ROOT);
    }
}
