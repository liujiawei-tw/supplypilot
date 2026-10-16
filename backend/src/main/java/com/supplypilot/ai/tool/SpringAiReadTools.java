package com.supplypilot.ai.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.supplypilot.ai.client.AiProviderException;
import com.supplypilot.ai.client.AiFailureDetail;
import com.supplypilot.ai.client.AiFailureReason;
import com.supplypilot.ai.dto.AiToolType;
import com.supplypilot.common.exception.ErrorCode;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** A request-scoped tool object. Only these six methods are exposed to the model. */
public final class SpringAiReadTools {
    private final AiToolRegistry registry;
    private final int maxToolCalls;
    private final String originalQuestion;
    private final String permittedMaterial;
    private final Long permittedRecommendation;
    private final List<AiToolResult> executions = new ArrayList<>();

    public SpringAiReadTools(AiToolRegistry registry, AiQueryPlanner planner,
                             String originalQuestion, int maxToolCalls) {
        this.registry = registry;
        this.maxToolCalls = maxToolCalls;
        this.originalQuestion = originalQuestion;
        this.permittedMaterial = planner.materialCode(originalQuestion);
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

    @Tool(name = "QUERY_OPEN_PO", description = "List open purchase orders, optionally filtered by one exact material code.")
    public String queryOpenPurchaseOrders(
            @ToolParam(description = "Exact material code, or omit to list all open purchase orders", required = false)
            @Nullable String materialCode
    ) {
        return execute(new AiQueryPlan(AiToolType.QUERY_OPEN_PO, null, materialCode, null));
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

    private String execute(AiQueryPlan plan) {
        if (executions.size() >= maxToolCalls) {
            throw new AiProviderException(ErrorCode.AI_INVALID_RESPONSE, AiFailureReason.TOOL_CALL_LIMIT);
        }
        if (plan.tool() != AiToolType.SEARCH_DOCUMENTS) {
            String material = plan.materialCode() == null ? null
                    : plan.materialCode().toUpperCase(Locale.ROOT);
            if (material != null && !material.equals(permittedMaterial)) {
                throw new AiProviderException(ErrorCode.AI_INVALID_RESPONSE,
                        AiFailureReason.UNAUTHORIZED_OR_MISMATCHED_TOOL_IDENTIFIER,
                        AiFailureDetail.MATERIAL_CODE_NOT_IN_QUESTION);
            }
            if (plan.recommendationId() != null && !plan.recommendationId().equals(permittedRecommendation)) {
                throw new AiProviderException(ErrorCode.AI_INVALID_RESPONSE,
                        AiFailureReason.UNAUTHORIZED_OR_MISMATCHED_TOOL_IDENTIFIER,
                        AiFailureDetail.RECOMMENDATION_ID_NOT_IN_QUESTION);
            }
            if (plan.tool() == AiToolType.QUERY_OPEN_PO && permittedMaterial != null && material == null) {
                throw new AiProviderException(ErrorCode.AI_INVALID_RESPONSE,
                        AiFailureReason.UNAUTHORIZED_OR_MISMATCHED_TOOL_IDENTIFIER,
                        AiFailureDetail.OPEN_PO_MATERIAL_FILTER_MISSING);
            }
        }
        AiToolResult result = registry.execute(plan);
        executions.add(result);
        // Model-facing data excludes source excerpts and bounds the outbound procurement payload.
        String facts = result.trustedFacts() == null ? "" : result.trustedFacts();
        return result.tool() + ": " + result.status() + "; "
                + facts.substring(0, Math.min(facts.length(), 1500));
    }
}
