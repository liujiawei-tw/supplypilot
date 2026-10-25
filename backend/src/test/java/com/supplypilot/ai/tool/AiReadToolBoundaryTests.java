package com.supplypilot.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.supplypilot.ai.dto.AiQueryStatus;
import com.supplypilot.ai.dto.AiToolType;
import com.supplypilot.common.pagination.PageResponse;
import com.supplypilot.document.domain.DocumentType;
import com.supplypilot.document.dto.response.DocumentQueryResponse;
import com.supplypilot.document.dto.response.DocumentQueryStatus;
import com.supplypilot.document.dto.response.DocumentSourceResponse;
import com.supplypilot.document.rag.DocumentQueryService;
import com.supplypilot.inventory.dto.response.InventoryResponse;
import com.supplypilot.inventory.service.InventoryQueryService;
import com.supplypilot.material.domain.BaseUnit;
import com.supplypilot.procurement.service.PurchaseOrderService;
import com.supplypilot.recommendation.domain.RecommendationOutcome;
import com.supplypilot.recommendation.dto.response.RecommendationResponse;
import com.supplypilot.recommendation.dto.response.SupplierRecommendationResultResponse;
import com.supplypilot.recommendation.service.PurchaseRecommendationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AiReadToolBoundaryTests {
    @Mock private PurchaseRecommendationService recommendations;
    @Mock private InventoryQueryService inventories;
    @Mock private PurchaseOrderService purchaseOrders;
    @Mock private DocumentQueryService documents;

    @Test
    void recommendationAndRankingUsePersistedSnapshotServiceMethods() {
        RecommendationResponse snapshot = snapshot();
        when(recommendations.getLatestRecommendationByMaterialCode("MAT-001")).thenReturn(snapshot);
        var recommendation = new RecommendationTool(recommendations).execute(
                new AiQueryPlan(AiToolType.EXPLAIN_RECOMMENDATION, null, "MAT-001", "why"));
        assertThat(recommendation.trustedFacts()).contains("200", "PURCHASE_NOT_REQUIRED");
        verify(recommendations).getLatestRecommendationByMaterialCode("MAT-001");

        SupplierRecommendationResultResponse candidate = new SupplierRecommendationResultResponse(
                101L, 9L, "SUP-009", "Supplier", true, null, new BigDecimal("200"),
                new BigDecimal("10"), new BigDecimal("2000"), 3,
                new BigDecimal("90"), new BigDecimal("91"), new BigDecimal("80"),
                new BigDecimal("85"), new BigDecimal("90"), new BigDecimal("91"),
                new BigDecimal("86.5"), 1);
        when(recommendations.getSupplierResults(41L)).thenReturn(List.of(candidate));
        var ranking = new RecommendationTool(recommendations).execute(
                new AiQueryPlan(AiToolType.QUERY_SUPPLIER_RANKING, null, "MAT-001", "ranking"));
        assertThat(ranking.trustedFacts()).contains("unitPrice=10 TWD", "totalCost=2000 TWD", "rank=1");
        verify(recommendations).getSupplierResults(41L);
    }

    @Test
    void inventoryUsesInventoryQueryService() {
        when(inventories.getInventoryByMaterialCode("MAT-001")).thenReturn(new InventoryResponse(
                5L, "MAT-001", "Material", new BigDecimal("18.5"), BaseUnit.KG,
                Instant.parse("2026-10-11T00:00:00Z")));
        var result = new InventoryTool(inventories).execute(
                new AiQueryPlan(AiToolType.QUERY_INVENTORY, null, "MAT-001", "inventory"));
        assertThat(result.trustedFacts()).contains("18.5", "KG");
        verify(inventories).getInventoryByMaterialCode("MAT-001");
    }

    @Test
    void openPoUsesOrderedStatusThroughPurchaseOrderService() {
        when(purchaseOrders.listOpen(ArgumentMatchers.isNull(), ArgumentMatchers.any()))
                .thenReturn(new PageResponse<>(List.of(), 0, 100, 0, 0));
        var result = new PurchaseOrderTool(purchaseOrders).execute(
                new AiQueryPlan(AiToolType.QUERY_OPEN_PO, null, null, "open po"));
        assertThat(result.trustedFacts()).contains("ORDERED", "總計 0 筆");
        verify(purchaseOrders).listOpen(ArgumentMatchers.isNull(), ArgumentMatchers.any());
    }

    @Test
    void documentSearchPreservesSourceVersionAndEvidenceStatus() {
        DocumentSourceResponse source = new DocumentSourceResponse(
                3L, DocumentType.SUPPLIER_CONTRACT, "Contract", "v2", 9L,
                "SUP-009", "Supplier Nine", 1, 10, 30, "evidence", 0.95);
        when(documents.query("付款條件")).thenReturn(new DocumentQueryResponse(
                DocumentQueryStatus.ANSWERED, "付款條件為月結。", List.of(source)));
        var result = new DocumentSearchTool(documents).execute(
                new AiQueryPlan(AiToolType.SEARCH_DOCUMENTS, null, null, "付款條件"));
        assertThat(result.sources()).singleElement().satisfies(mapped -> {
            assertThat(mapped.resourceId()).isEqualTo("3");
            assertThat(mapped.version()).isEqualTo("v2");
            assertThat(mapped.excerpt()).isEqualTo("evidence");
        });

        when(documents.query("未知")).thenReturn(DocumentQueryResponse.insufficientEvidence());
        assertThat(new DocumentSearchTool(documents).execute(
                new AiQueryPlan(AiToolType.SEARCH_DOCUMENTS, null, null, "未知")).status())
                .isEqualTo(AiQueryStatus.INSUFFICIENT_EVIDENCE);
    }

    private RecommendationResponse snapshot() {
        return new RecommendationResponse(
                41L,
                new RecommendationResponse.MaterialSnapshot(5L, "MAT-001", "Material", "KG"),
                new RecommendationResponse.Period(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)),
                new RecommendationResponse.Calculation(new BigDecimal("100"), new BigDecimal("20"),
                        new BigDecimal("200"), BigDecimal.ZERO, BigDecimal.ZERO),
                RecommendationOutcome.PURCHASE_NOT_REQUIRED,
                new RecommendationResponse.RecommendedSupplier(false, null, null, null, null),
                Instant.parse("2026-10-11T00:00:00Z"));
    }
}
