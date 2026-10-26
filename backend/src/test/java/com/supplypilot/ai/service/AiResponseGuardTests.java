package com.supplypilot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.supplypilot.ai.client.AiFailureReason;
import com.supplypilot.ai.client.AiProviderException;
import com.supplypilot.ai.dto.AiQueryStatus;
import com.supplypilot.ai.dto.AiSourceResponse;
import com.supplypilot.ai.dto.AiToolType;
import com.supplypilot.ai.tool.AiToolResult;
import com.supplypilot.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

class AiResponseGuardTests {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AiResponseGuard guard = new AiResponseGuard(mapper);

    @Test
    void returnsVerifiedSummarySeparatelyFromBackendFacts() {
        AiToolResult executed = result("目前庫存=100 KG；建議採購量=200 KG");
        var answer = guard.compose("{\"summary\":\"已依據查得的資料整理以下結果。\",\"factIndexes\":[0]}",
                List.of(executed));
        assertThat(answer.answerSource()).isEqualTo("MODEL_EXPLANATION");
        assertThat(answer.summary()).isEqualTo("已依據查得的資料整理以下結果。");
        assertThat(answer.answer()).isEqualTo(executed.trustedFacts());
    }

    @Test
    void swapsOfTrustedNumbersFallBackToBackendFacts() {
        AiToolResult executed = result("目前庫存=100 KG；建議採購量=200 KG");
        var answer = guard.compose("目前庫存=200 KG；建議採購量=100 KG", List.of(executed));
        assertThat(answer.answerSource()).isEqualTo("BACKEND_FACTS");
        assertThat(answer.summary()).isNull();
        assertThat(answer.diagnosticReason()).isEqualTo(AiFailureReason.INVALID_FINAL_RESPONSE_STRUCTURE);
        assertThat(answer.answer()).isEqualTo(executed.trustedFacts());
    }

    @Test
    void rejectsChangedRelationshipsAndUnexecutedPayloads() {
        AiToolResult executed = result("supplier=SUP-001；material=MAT-001；unitPrice=10 TWD；totalCost=2000 TWD");
        var answer = guard.compose("supplier=SUP-002；material=MAT-001；unitPrice=10 USD；totalCost=2000 USD",
                List.of(executed));
        assertThat(answer.answer()).isEqualTo(executed.trustedFacts());
        assertThat(answer.answer()).doesNotContain("SUP-002", "USD");
        assertThatThrownBy(() -> guard.compose("{}", List.of()))
                .isInstanceOf(AiProviderException.class).extracting("errorCode")
                .isEqualTo(ErrorCode.AI_INVALID_RESPONSE);
        assertThatThrownBy(() -> guard.compose("{}", List.of()))
                .isInstanceOf(AiProviderException.class).extracting("failureReason")
                .isEqualTo(AiFailureReason.NO_TOOL_EXECUTION);
    }

    @Test
    void preservesAllSourcesAndFallsBackWhenModelOmitsOneFact() {
        AiToolResult inventory = result("MAT-001 庫存=100 KG");
        AiToolResult po = new AiToolResult(AiToolType.QUERY_OPEN_PO, AiQueryStatus.ANSWERED,
                "PO-1 總成本=200 TWD", List.of(AiSourceResponse.resource("PURCHASE_ORDER", 2, "PO-1")));
        var answer = guard.compose("{\"summary\":\"已依據查得的資料整理以下結果。\",\"factIndexes\":[0]}",
                List.of(inventory, po));
        assertThat(answer.answerSource()).isEqualTo("BACKEND_FACTS");
        assertThat(answer.diagnosticReason()).isEqualTo(AiFailureReason.INVALID_FINAL_RESPONSE_STRUCTURE);
        assertThat(answer.answer()).contains("100 KG", "200 TWD");
    }

    @Test
    void rejectsUnsupportedSummaryAndKeepsOnlyBackendOwnedFacts() {
        AiToolResult executed = result("目前庫存=100 KG");
        var answer = guard.compose("{\"summary\":\"目前庫存為 999 KG。\",\"factIndexes\":[0]}", List.of(executed));

        assertThat(answer.summary()).isNull();
        assertThat(answer.answerSource()).isEqualTo("BACKEND_FACTS");
        assertThat(answer.answer()).isEqualTo(executed.trustedFacts());
        assertThat(answer.answer()).doesNotContain("999");
    }

    @Test
    void invalidJsonFallsBackWithoutShowingAnAiSummary() {
        AiToolResult executed = result("目前庫存=100 KG");
        var answer = guard.compose("not-json", List.of(executed));

        assertThat(answer.summary()).isNull();
        assertThat(answer.answerSource()).isEqualTo("BACKEND_FACTS");
        assertThat(answer.answer()).isEqualTo(executed.trustedFacts());
    }

    @Test
    void identifiesDocumentEvidenceGapsWithoutMislabelingBusinessMisses() {
        AiToolResult inventory = result("MAT-001 庫存=100 KG");
        AiToolResult documents = AiToolResult.insufficientEvidence(AiToolType.SEARCH_DOCUMENTS);
        var answer = guard.compose("{\"summary\":\"文件檢索未提供足夠證據，未據此作肯定結論。\",\"factIndexes\":[0]}",
                List.of(inventory, documents));

        assertThat(answer.summary()).isEqualTo("文件檢索未提供足夠證據，未據此作肯定結論。");
        assertThat(answer.answer()).contains("MAT-001 庫存=100 KG", "文件檢索證據不足");
        assertThat(answer.answer()).doesNotContain("文件證據不足");
    }

    @Test
    void includesExplicitBusinessNotFoundFactsAlongsideSuccessfulResults() {
        AiToolResult inventory = result("MAT-001 庫存=100 KG");
        AiToolResult missing = AiToolResult.notFound(AiToolType.QUERY_SUPPLIER,
                "查無供應商代碼 SUP-999 的主檔資料。", com.supplypilot.ai.dto.AiAnswerData.empty());
        var answer = guard.compose("{\"summary\":\"已查得部分指定資料，未找到的項目列於下方。\",\"factIndexes\":[0]}",
                List.of(inventory, missing));

        assertThat(answer.answer()).contains("MAT-001 庫存=100 KG", "查無供應商代碼 SUP-999");
        assertThat(answer.answer()).doesNotContain("文件檢索證據不足");
        assertThat(answer.summary()).isEqualTo("已查得部分指定資料，未找到的項目列於下方。");
    }

    @Test
    void distinguishesInsufficientBusinessDataFromDocumentSearchGaps() {
        AiToolResult inventory = result("MAT-001 庫存=100 KG");
        AiToolResult business = AiToolResult.insufficientEvidence(AiToolType.QUERY_SUPPLIER);
        var answer = guard.compose("{\"summary\":\"部分業務查詢未取得足夠證據；已查得內容列於下方。\",\"factIndexes\":[0]}",
                List.of(inventory, business));

        assertThat(answer.answer()).contains("MAT-001 庫存=100 KG", "此項業務查詢未取得足夠證據");
        assertThat(answer.answer()).doesNotContain("文件檢索");
        assertThat(answer.summary()).isEqualTo("部分業務查詢未取得足夠證據；已查得內容列於下方。");
    }

    private AiToolResult result(String facts) {
        return new AiToolResult(AiToolType.EXPLAIN_RECOMMENDATION, AiQueryStatus.ANSWERED, facts,
                List.of(AiSourceResponse.resource("PURCHASE_RECOMMENDATION", 1, "MAT-001")));
    }
}
