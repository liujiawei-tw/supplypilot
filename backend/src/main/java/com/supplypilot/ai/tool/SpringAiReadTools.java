package com.supplypilot.ai.tool;

import java.util.ArrayList;
import java.util.List;

import com.supplypilot.ai.dto.AiToolType;
import org.jspecify.annotations.Nullable;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** A request-scoped tool object. Only these six methods are exposed to the model. */
public final class SpringAiReadTools {
    private final AiToolRegistry registry;
    private final int maxToolCalls;
    private final List<AiToolResult> executions = new ArrayList<>();

    public SpringAiReadTools(AiToolRegistry registry, int maxToolCalls) {
        this.registry = registry;
        this.maxToolCalls = maxToolCalls;
    }

    @Tool(name = "QUERY_RECOMMENDATION", description = "Read one saved purchase recommendation by recommendation ID or material code.", returnDirect = true)
    public AiToolResult queryRecommendation(
            @ToolParam(description = "Saved recommendation ID", required = false) @Nullable Long recommendationId,
            @ToolParam(description = "Exact material code such as MAT-001", required = false) @Nullable String materialCode
    ) {
        return execute(new AiQueryPlan(AiToolType.QUERY_RECOMMENDATION, recommendationId, materialCode, null));
    }

    @Tool(name = "EXPLAIN_RECOMMENDATION", description = "Return authoritative saved calculation facts explaining one purchase recommendation.", returnDirect = true)
    public AiToolResult explainRecommendation(
            @ToolParam(description = "Saved recommendation ID", required = false) @Nullable Long recommendationId,
            @ToolParam(description = "Exact material code such as MAT-001", required = false) @Nullable String materialCode
    ) {
        return execute(new AiQueryPlan(AiToolType.EXPLAIN_RECOMMENDATION, recommendationId, materialCode, null));
    }

    @Tool(name = "QUERY_SUPPLIER_RANKING", description = "Read the saved supplier ranking for one recommendation; never recomputes scores.", returnDirect = true)
    public AiToolResult querySupplierRanking(
            @ToolParam(description = "Saved recommendation ID", required = false) @Nullable Long recommendationId,
            @ToolParam(description = "Exact material code such as MAT-001", required = false) @Nullable String materialCode
    ) {
        return execute(new AiQueryPlan(AiToolType.QUERY_SUPPLIER_RANKING, recommendationId, materialCode, null));
    }

    @Tool(name = "QUERY_INVENTORY", description = "Read current inventory for one exact material code.", returnDirect = true)
    public AiToolResult queryInventory(
            @ToolParam(description = "Exact material code such as MAT-001") String materialCode
    ) {
        return execute(new AiQueryPlan(AiToolType.QUERY_INVENTORY, null, materialCode, null));
    }

    @Tool(name = "QUERY_OPEN_PO", description = "List open purchase orders, optionally filtered by one exact material code.", returnDirect = true)
    public AiToolResult queryOpenPurchaseOrders(
            @ToolParam(description = "Exact material code, or omit to list all open purchase orders", required = false)
            @Nullable String materialCode
    ) {
        return execute(new AiQueryPlan(AiToolType.QUERY_OPEN_PO, null, materialCode, null));
    }

    @Tool(name = "SEARCH_DOCUMENTS", description = "Search indexed SP-710 document evidence for a procurement question.", returnDirect = true)
    public AiToolResult searchDocuments(
            @ToolParam(description = "The procurement document question") String question
    ) {
        return execute(new AiQueryPlan(AiToolType.SEARCH_DOCUMENTS, null, null, question));
    }

    public List<AiToolResult> executions() {
        return List.copyOf(executions);
    }

    private AiToolResult execute(AiQueryPlan plan) {
        if (executions.size() >= maxToolCalls) {
            throw new IllegalStateException("AI tool call limit exceeded.");
        }
        AiToolResult result = registry.execute(plan);
        executions.add(result);
        return result;
    }
}
