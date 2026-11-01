package com.supplypilot.document.rag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.supplypilot.ai.tool.AiQueryPlanner;
import com.supplypilot.document.semantic.SemanticProperties;
import org.springframework.ai.rag.Query;
import org.springframework.ai.rag.preretrieval.query.transformation.QueryTransformer;
import org.springframework.ai.rag.retrieval.search.VectorStoreDocumentRetriever;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

/** Adds explicit supplier scope to the Spring AI vector-store search request. */
final class SupplierScopedRagQueryTransformer implements QueryTransformer {
    private final AiQueryPlanner queryPlanner;
    private final SemanticProperties properties;

    SupplierScopedRagQueryTransformer(AiQueryPlanner queryPlanner, SemanticProperties properties) {
        this.queryPlanner = queryPlanner;
        this.properties = properties;
    }

    @Override
    public Query transform(Query query) {
        List<String> supplierCodes = queryPlanner.supplierCodes(query.text()).stream()
                .map(SupplierScopedRagQueryTransformer::canonicalSupplierCode)
                .distinct()
                .toList();

        Map<String, Object> context = new HashMap<>(query.context());
        context.put(VectorStoreDocumentRetriever.FILTER_EXPRESSION, supplierCodes.isEmpty()
                ? publishedApprovedGenerationFilter()
                : supplierScopedFilter(supplierCodes));
        return query.mutate().context(context).build();
    }

    Filter.Expression publishedApprovedGenerationFilter() {
        FilterExpressionBuilder filters = new FilterExpressionBuilder();
        return publishedApprovedGenerationFilter(filters).build();
    }

    private FilterExpressionBuilder.Op publishedApprovedGenerationFilter(FilterExpressionBuilder filters) {
        return filters.and(
                filters.and(filters.eq("generationKey", properties.generationKey()),
                        filters.eq("documentStatus", "READY")),
                filters.eq("outboundApprovalStatus", "APPROVED"));
    }

    private Filter.Expression supplierScopedFilter(List<String> supplierCodes) {
        FilterExpressionBuilder filters = new FilterExpressionBuilder();
        List<Object> codes = new ArrayList<>(supplierCodes);
        return filters.and(publishedApprovedGenerationFilter(filters),
                filters.in("supplierCode", codes)).build();
    }

    private static String canonicalSupplierCode(String supplierCode) {
        String normalized = supplierCode.toUpperCase(Locale.ROOT);
        return normalized.startsWith("SUPPLIER-")
                ? "SUP-" + normalized.substring("SUPPLIER-".length())
                : normalized;
    }
}
