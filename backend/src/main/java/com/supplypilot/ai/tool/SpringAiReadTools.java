package com.supplypilot.ai.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.supplypilot.ai.client.AiProviderException;
import com.supplypilot.ai.client.AiFailureDetail;
import com.supplypilot.ai.client.AiFailureReason;
import com.supplypilot.ai.dto.AiToolType;
import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** A request-scoped tool object. Only these six methods are exposed to the model. */
public final class SpringAiReadTools {
    private final AiToolRegistry registry;
    private final int maxToolCalls;
    private final String originalQuestion;
    private final Set<String> permittedMaterials;
    private final Set<String> permittedSuppliers;
    private final String permittedSupplierName;
    private final String permittedPurchaseOrder;
    private final Long permittedRecommendation;
    private final List<AiToolResult> executions = new ArrayList<>();
    private int ignoredOptionalRecommendationMisses;

    public SpringAiReadTools(AiToolRegistry registry, AiQueryPlanner planner,
                             String originalQuestion, int maxToolCalls) {
        this.registry = registry;
        this.maxToolCalls = maxToolCalls;
        this.originalQuestion = originalQuestion;
        this.permittedMaterials = Set.copyOf(planner.materialCodes(originalQuestion));
        this.permittedSuppliers = Set.copyOf(planner.supplierCodes(originalQuestion));
        this.permittedSupplierName = planner.supplierName(originalQuestion);
        this.permittedPurchaseOrder = planner.purchaseOrderNumber(originalQuestion);
        this.permittedRecommendation = planner.recommendationId(originalQuestion);
    }

    @Tool(name = "QUERY_RECOMMENDATION", description = "Read one saved purchase recommendation by recommendation ID or material code.")
    public String queryRecommendation(
            @ToolParam(description = "Saved recommendation ID", required = false) @Nullable Long recommendationId,
            @ToolParam(description = "Exact material code such as MAT-001", required = false) @Nullable String materialCode
    ) {
        return execute(new AiQueryPlan(AiToolType.QUERY_RECOMMENDATION, recommendationId, materialCode, null));
    }

    @Tool(name = "EXPLAIN_RECOMMENDATION", description = "Return authoritative saved calculation facts explaining one purchase recommendation.")
    public String explainRecommendation(
            @ToolParam(description = "Saved recommendation ID", required = false) @Nullable Long recommendationId,
            @ToolParam(description = "Exact material code such as MAT-001", required = false) @Nullable String materialCode
    ) {
        return execute(new AiQueryPlan(AiToolType.EXPLAIN_RECOMMENDATION, recommendationId, materialCode, null));
    }

    @Tool(name = "QUERY_SUPPLIER_RANKING", description = "Read the saved supplier ranking for one recommendation; never recomputes scores.")
    public String querySupplierRanking(
            @ToolParam(description = "Saved recommendation ID", required = false) @Nullable Long recommendationId,
            @ToolParam(description = "Exact material code such as MAT-001", required = false) @Nullable String materialCode
    ) {
        return execute(new AiQueryPlan(AiToolType.QUERY_SUPPLIER_RANKING, recommendationId, materialCode, null));
    }

    @Tool(name = "QUERY_INVENTORY", description = "Read current inventory for one exact material code.")
    public String queryInventory(
            @ToolParam(description = "Exact material code such as MAT-001") String materialCode
    ) {
        return execute(new AiQueryPlan(AiToolType.QUERY_INVENTORY, null, materialCode, null));
    }

    @Tool(name = "QUERY_INVENTORY_BATCH", description = "Read current inventory for every exact material code listed in the user question in one batch.")
    public String queryInventoryBatch(
            @ToolParam(description = "All exact material codes explicitly present in the question") List<String> materialCodes
    ) {
        List<String> codes = materialCodes == null ? List.of() : List.copyOf(materialCodes);
        return execute(new AiQueryPlan(AiToolType.QUERY_INVENTORY, null,
                codes.isEmpty() ? null : codes.getFirst(), null, codes, null, null, null, null));
    }

    @Tool(name = "QUERY_INVENTORY_BY_NAME", description = "Resolve a material name against the live material master, then read inventory when the match is unique.")
    public String queryInventoryByName(
            @ToolParam(description = "Material name written literally in the user question") String materialName
    ) {
        return execute(new AiQueryPlan(AiToolType.QUERY_INVENTORY, null, null, null,
                List.of(), materialName, null, null, null));
    }

    @Tool(name = "QUERY_SUPPLIER_QUOTES", description = "Read active supplier-material quotes from current master data; includes supplier, material, price, currency, base unit and lead time.")
    public String querySupplierQuotes(
            @ToolParam(description = "Exact material codes explicitly present in the question", required = false) @Nullable List<String> materialCodes,
            @ToolParam(description = "Material name written literally in the question", required = false) @Nullable String materialName
    ) {
        return execute(new AiQueryPlan(AiToolType.QUERY_SUPPLIER_QUOTES, null,
                materialCodes == null || materialCodes.isEmpty() ? null : materialCodes.getFirst(), null,
                materialCodes == null ? List.of() : materialCodes, materialName, null, null, null));
    }

    @Tool(name = "QUERY_AVERAGE_PRICE", description = "Calculate deterministic arithmetic averages from active current supplier-material quotes, grouped by currency and pricing unit.")
    public String queryAveragePrice(
            @ToolParam(description = "Exact material codes explicitly present in the question", required = false) @Nullable List<String> materialCodes,
            @ToolParam(description = "Material name written literally in the question", required = false) @Nullable String materialName
    ) {
        return execute(new AiQueryPlan(AiToolType.QUERY_AVERAGE_PRICE, null,
                materialCodes == null || materialCodes.isEmpty() ? null : materialCodes.getFirst(), null,
                materialCodes == null ? List.of() : materialCodes, materialName, null, null, null));
    }

    @Tool(name = "QUERY_SUPPLIER", description = "Read a supplier master record by exact supplier code/name, or active suppliers linked to the exact material codes/name in the question.")
    public String querySupplier(
            @ToolParam(description = "Exact supplier code such as SUP-002", required = false) @Nullable String supplierCode,
            @ToolParam(description = "All exact supplier codes explicitly present in the question", required = false) @Nullable List<String> supplierCodes,
            @ToolParam(description = "Supplier name written literally in the question", required = false) @Nullable String supplierName,
            @ToolParam(description = "Exact material codes explicitly present in the question", required = false) @Nullable List<String> materialCodes,
            @ToolParam(description = "Material name written literally in the question", required = false) @Nullable String materialName
    ) {
        List<String> codes = supplierCodes == null ? List.of() : List.copyOf(supplierCodes);
        if (supplierCode != null) codes = java.util.stream.Stream.concat(codes.stream(), java.util.stream.Stream.of(supplierCode))
                .distinct().toList();
        String firstSupplierCode = codes.isEmpty() ? supplierCode : codes.getFirst();
        return execute(new AiQueryPlan(AiToolType.QUERY_SUPPLIER, null,
                materialCodes == null || materialCodes.isEmpty() ? null : materialCodes.getFirst(), null,
                materialCodes == null ? List.of() : materialCodes, materialName, firstSupplierCode, supplierName,
                null, codes));
    }

    @Tool(name = "QUERY_PURCHASE_ORDER", description = "Read exactly one purchase order by its complete PO number. Returns lifecycle status, separate Kanban stage, supplier, dates, line items and backend totals.")
    public String queryPurchaseOrder(
            @ToolParam(description = "Complete PO number explicitly present in the question, such as PO-20261102-000004") String poNumber
    ) {
        return execute(new AiQueryPlan(AiToolType.QUERY_PURCHASE_ORDER, null, null, null,
                List.of(), null, null, null, poNumber));
    }

    @Tool(name = "QUERY_OPEN_PO", description = "List open purchase orders, optionally filtered by all exact material codes explicitly present in the question.")
    public String queryOpenPurchaseOrders(
            @ToolParam(description = "One exact material code, retained for compatibility", required = false)
            @Nullable String materialCode,
            @ToolParam(description = "All exact material codes explicitly present in the question", required = false)
            @Nullable List<String> materialCodes
    ) {
        List<String> codes = materialCodes == null ? List.of() : List.copyOf(materialCodes);
        if (materialCode != null) codes = java.util.stream.Stream.concat(codes.stream(), java.util.stream.Stream.of(materialCode))
                .distinct().toList();
        String firstCode = codes.isEmpty() ? null : codes.getFirst();
        return execute(new AiQueryPlan(AiToolType.QUERY_OPEN_PO, null, firstCode, null,
                codes, null, null, null, null, List.of()));
    }

    @Tool(name = "SEARCH_DOCUMENTS", description = "Search indexed SP-710 document evidence for a procurement question.")
    public String searchDocuments(
            @ToolParam(description = "The procurement document question") String question
    ) {
        return execute(new AiQueryPlan(AiToolType.SEARCH_DOCUMENTS, null, null, originalQuestion));
    }

    public List<AiToolResult> executions() {
        return List.copyOf(executions);
    }

    public int ignoredOptionalRecommendationMisses() {
        return ignoredOptionalRecommendationMisses;
    }

    private String execute(AiQueryPlan plan) {
        if (executions.size() >= maxToolCalls) {
            throw new AiProviderException(ErrorCode.AI_INVALID_RESPONSE, AiFailureReason.TOOL_CALL_LIMIT);
        }
        if (plan.tool() != AiToolType.SEARCH_DOCUMENTS) {
            List<String> materialCodes = new ArrayList<>(plan.materialCodes());
            if (plan.materialCode() != null) materialCodes.add(plan.materialCode());
            boolean unauthorizedMaterial = materialCodes.stream().map(value -> value.toUpperCase(Locale.ROOT))
                    .anyMatch(value -> !permittedMaterials.contains(value));
            if (unauthorizedMaterial) {
                throw new AiProviderException(ErrorCode.AI_INVALID_RESPONSE,
                        AiFailureReason.UNAUTHORIZED_OR_MISMATCHED_TOOL_IDENTIFIER,
                        AiFailureDetail.MATERIAL_CODE_NOT_IN_QUESTION);
            }
            if (hasText(plan.materialName()) && !containsLiteral(originalQuestion, plan.materialName())) {
                throw new AiProviderException(ErrorCode.AI_INVALID_RESPONSE,
                        AiFailureReason.UNAUTHORIZED_OR_MISMATCHED_TOOL_IDENTIFIER,
                        AiFailureDetail.MATERIAL_NAME_NOT_IN_QUESTION);
            }
            if (hasText(plan.supplierName()) && (permittedSupplierName == null
                    || !plan.supplierName().trim().equalsIgnoreCase(permittedSupplierName))) {
                throw new AiProviderException(ErrorCode.AI_INVALID_RESPONSE,
                        AiFailureReason.UNAUTHORIZED_OR_MISMATCHED_TOOL_IDENTIFIER,
                        AiFailureDetail.SUPPLIER_NAME_NOT_IN_QUESTION);
            }
            List<String> requestedSupplierCodes = new ArrayList<>(plan.supplierCodes());
            if (plan.supplierCode() != null) requestedSupplierCodes.add(plan.supplierCode());
            boolean unauthorizedSupplier = requestedSupplierCodes.stream()
                    .map(value -> value.toUpperCase(Locale.ROOT))
                    .anyMatch(value -> !permittedSuppliers.contains(value));
            if (unauthorizedSupplier) {
                throw new AiProviderException(ErrorCode.AI_INVALID_RESPONSE,
                        AiFailureReason.UNAUTHORIZED_OR_MISMATCHED_TOOL_IDENTIFIER,
                        AiFailureDetail.SUPPLIER_CODE_NOT_IN_QUESTION);
            }
            if (plan.purchaseOrderNumber() != null
                    && !plan.purchaseOrderNumber().equalsIgnoreCase(permittedPurchaseOrder)) {
                throw new AiProviderException(ErrorCode.AI_INVALID_RESPONSE,
                        AiFailureReason.UNAUTHORIZED_OR_MISMATCHED_TOOL_IDENTIFIER,
                        AiFailureDetail.PURCHASE_ORDER_NUMBER_NOT_IN_QUESTION);
            }
            if (plan.recommendationId() != null && !plan.recommendationId().equals(permittedRecommendation)) {
                throw new AiProviderException(ErrorCode.AI_INVALID_RESPONSE,
                        AiFailureReason.UNAUTHORIZED_OR_MISMATCHED_TOOL_IDENTIFIER,
                        AiFailureDetail.RECOMMENDATION_ID_NOT_IN_QUESTION);
            }
            if (plan.tool() == AiToolType.QUERY_OPEN_PO && !permittedMaterials.isEmpty() && materialCodes.isEmpty()) {
                throw new AiProviderException(ErrorCode.AI_INVALID_RESPONSE,
                        AiFailureReason.UNAUTHORIZED_OR_MISMATCHED_TOOL_IDENTIFIER,
                        AiFailureDetail.OPEN_PO_MATERIAL_FILTER_MISSING);
            }
        }
        AiToolResult result;
        try {
            result = registry.execute(plan);
        } catch (BusinessException failure) {
            if (failure.getErrorCode() != ErrorCode.RECOMMENDATION_NOT_FOUND
                    || !isRecommendationTool(plan.tool())
                    || AiQuestionIntent.requiresRecommendation(originalQuestion)) {
                throw failure;
            }
            ignoredOptionalRecommendationMisses++;
            result = AiToolResult.insufficientEvidence(plan.tool());
        }
        executions.add(result);
        // Model-facing data excludes source excerpts and bounds the outbound procurement payload.
        String facts = result.trustedFacts() == null ? "" : result.trustedFacts();
        return result.tool() + ": " + result.status() + "; "
                + facts.substring(0, Math.min(facts.length(), 1500));
    }

    private boolean isRecommendationTool(AiToolType tool) {
        return tool == AiToolType.QUERY_RECOMMENDATION || tool == AiToolType.EXPLAIN_RECOMMENDATION
                || tool == AiToolType.QUERY_SUPPLIER_RANKING;
    }

    private boolean containsLiteral(String question, String value) {
        return question.toLowerCase(Locale.ROOT).contains(value.trim().toLowerCase(Locale.ROOT));
    }

    private boolean hasText(String value) { return value != null && !value.isBlank(); }
}
