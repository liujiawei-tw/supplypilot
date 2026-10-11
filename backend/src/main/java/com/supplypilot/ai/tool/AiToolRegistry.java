package com.supplypilot.ai.tool;

import org.springframework.stereotype.Component;

@Component
public class AiToolRegistry {
    private final RecommendationTool recommendation;
    private final InventoryTool inventory;
    private final PurchaseOrderTool purchaseOrders;
    private final DocumentSearchTool documents;

    public AiToolRegistry(
            RecommendationTool recommendation,
            InventoryTool inventory,
            PurchaseOrderTool purchaseOrders,
            DocumentSearchTool documents
    ) {
        this.recommendation = recommendation;
        this.inventory = inventory;
        this.purchaseOrders = purchaseOrders;
        this.documents = documents;
    }

    public AiToolResult execute(AiQueryPlan plan) {
        return switch (plan.tool()) {
            case QUERY_RECOMMENDATION, EXPLAIN_RECOMMENDATION, QUERY_SUPPLIER_RANKING -> recommendation.execute(plan);
            case QUERY_INVENTORY -> inventory.execute(plan);
            case QUERY_OPEN_PO -> purchaseOrders.execute(plan);
            case SEARCH_DOCUMENTS -> documents.execute(plan);
        };
    }
}
