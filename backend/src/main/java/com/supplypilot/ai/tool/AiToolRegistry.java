package com.supplypilot.ai.tool;

import org.springframework.stereotype.Component;

@Component
public class AiToolRegistry {
    private final RecommendationTool recommendation;
    private final DocumentSearchTool documents;
    private final AiProcurementReadService procurementReads;

    public AiToolRegistry(
            RecommendationTool recommendation,
            DocumentSearchTool documents,
            AiProcurementReadService procurementReads
    ) {
        this.recommendation = recommendation;
        this.documents = documents;
        this.procurementReads = procurementReads;
    }

    public AiToolResult execute(AiQueryPlan plan) {
        return switch (plan.tool()) {
            case QUERY_RECOMMENDATION, EXPLAIN_RECOMMENDATION, QUERY_SUPPLIER_RANKING -> recommendation.execute(plan);
            case QUERY_INVENTORY -> procurementReads.inventory(
                    plan.materialCodes().isEmpty() && plan.materialCode() != null
                            ? java.util.List.of(plan.materialCode()) : plan.materialCodes(), plan.materialName());
            case QUERY_SUPPLIER_QUOTES -> procurementReads.supplierQuotes(
                    plan.materialCodes(), plan.materialName(), false);
            case QUERY_AVERAGE_PRICE -> procurementReads.supplierQuotes(
                    plan.materialCodes(), plan.materialName(), true);
            case QUERY_SUPPLIER -> procurementReads.suppliers(plan.supplierCodes(), plan.supplierName(),
                    plan.materialCodes(), plan.materialName());
            case QUERY_PURCHASE_ORDER -> procurementReads.purchaseOrder(plan.purchaseOrderNumber());
            case QUERY_OPEN_PO -> procurementReads.openPurchaseOrders(plan.materialCodes().isEmpty()
                    && plan.materialCode() != null ? java.util.List.of(plan.materialCode()) : plan.materialCodes());
            case SEARCH_DOCUMENTS -> documents.execute(plan);
        };
    }
}
