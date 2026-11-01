package com.supplypilot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import com.supplypilot.ai.dto.AiAnswerData;
import com.supplypilot.ai.dto.AiQueryStatus;
import com.supplypilot.ai.dto.AiSourceResponse;
import com.supplypilot.ai.dto.AiToolType;
import com.supplypilot.ai.tool.AiQueryPlanner;
import com.supplypilot.ai.tool.AiToolResult;
import org.junit.jupiter.api.Test;

class AiToolResultPolicyTests {
    private final AiToolResultPolicy policy = new AiToolResultPolicy(new AiQueryPlanner());

    @Test
    void rejectsCompoundRequestWhenOneRequestedToolIsMissingButPreservesPartialResult() {
        String question = "MAT-001 庫存與 SUP-001 供應商狀態";
        AiToolResult inventory = inventoryResult(List.of("MAT-001"));
        Set<AiToolType> requested = Set.of(AiToolType.QUERY_INVENTORY, AiToolType.QUERY_SUPPLIER);

        assertThat(policy.supportingResults(question, requested, List.of(inventory))).isEmpty();
        assertThat(policy.partialResults(question, requested, List.of(inventory))).containsExactly(inventory);
        assertThat(policy.incompleteNotes(question, requested, List.of(inventory), List.of(inventory)))
                .contains("尚未取得可信的供應商主檔查詢結果。", "尚未取得以下供應商代碼的結果：SUP-001。");
    }

    @Test
    void rejectsToolResultWhenItDoesNotCoverEveryExplicitlyRequestedMaterial() {
        String question = "MAT-001 與 MAT-002 的供應商報價";
        AiAnswerData details = new AiAnswerData(List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), null, List.of("MAT-001"));
        AiToolResult quotes = new AiToolResult(AiToolType.QUERY_SUPPLIER_QUOTES, AiQueryStatus.ANSWERED,
                "已查詢 MAT-001", List.of(), details);

        assertThat(policy.supportingResults(question, Set.of(AiToolType.QUERY_SUPPLIER_QUOTES), List.of(quotes)))
                .isEmpty();
        assertThat(policy.incompleteNotes(question, Set.of(AiToolType.QUERY_SUPPLIER_QUOTES), List.of(quotes),
                List.of(quotes))).contains("尚未取得以下供應商報價查詢物料結果：MAT-002。");
    }

    @Test
    void rejectsSupplierToolResultThatDoesNotCoverTheExplicitSupplierCode() {
        String question = "SUP-001 供應商狀態";
        AiAnswerData details = new AiAnswerData(List.of(), List.of(), List.of(),
                List.of(new AiAnswerData.SupplierFact(2L, "SUP-002", "Other supplier", "ACTIVE", null, null)),
                List.of(), List.of(), List.of(), List.of());
        AiToolResult supplier = new AiToolResult(AiToolType.QUERY_SUPPLIER, AiQueryStatus.ANSWERED,
                "供應商資料", List.of(AiSourceResponse.resource("SUPPLIER", 2, "SUP-002")), details);

        assertThat(policy.supportingResults(question, Set.of(AiToolType.QUERY_SUPPLIER), List.of(supplier)))
                .isEmpty();
        assertThat(policy.incompleteNotes(question, Set.of(AiToolType.QUERY_SUPPLIER), List.of(supplier), List.of(supplier)))
                .contains("尚未取得以下供應商代碼的結果：SUP-001。");
    }

    @Test
    void refusesEmptyIntentAndInsufficientEvidence() {
        AiToolResult insufficient = AiToolResult.insufficientEvidence(AiToolType.QUERY_INVENTORY);

        assertThat(policy.supportingResults("MAT-001 庫存", Set.of(), List.of(insufficient))).isEmpty();
        assertThat(policy.supportingResults("MAT-001 庫存", Set.of(AiToolType.QUERY_INVENTORY),
                List.of(insufficient))).isEmpty();
        assertThat(policy.partialResults("MAT-001 庫存", Set.of(), List.of(insufficient))).isEmpty();
    }

    private AiToolResult inventoryResult(List<String> queriedMaterialCodes) {
        AiAnswerData details = new AiAnswerData(
                List.of(new AiAnswerData.InventoryItem(1L, "MAT-001", "Material", null, "KG", null)),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null,
                queriedMaterialCodes);
        return new AiToolResult(AiToolType.QUERY_INVENTORY, AiQueryStatus.ANSWERED,
                "MAT-001 庫存已查得", List.of(AiSourceResponse.resource("INVENTORY", 1, "MAT-001")), details);
    }
}
