package com.supplypilot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
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
    void acceptsConstrainedModelIntroductionAndAppendsOnlyBackendFacts() {
        AiToolResult executed = result("目前庫存=100 KG；建議採購量=200 KG");
        var answer = guard.compose("{\"intro\":\"查詢結果整理如下。\",\"factIndexes\":[0]}",
                List.of(executed));
        assertThat(answer.answerSource()).isEqualTo("MODEL_EXPLANATION");
        assertThat(answer.answer()).contains(executed.trustedFacts());
    }

    @Test
    void swapsOfTrustedNumbersFallBackToBackendFacts() {
        AiToolResult executed = result("目前庫存=100 KG；建議採購量=200 KG");
        var answer = guard.compose("目前庫存=200 KG；建議採購量=100 KG", List.of(executed));
        assertThat(answer.answerSource()).isEqualTo("BACKEND_FACTS");
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
    }

    @Test
    void preservesAllSourcesAndFallsBackWhenModelOmitsOneFact() {
        AiToolResult inventory = result("MAT-001 庫存=100 KG");
        AiToolResult po = new AiToolResult(AiToolType.QUERY_OPEN_PO, AiQueryStatus.ANSWERED,
                "PO-1 總成本=200 TWD", List.of(AiSourceResponse.resource("PURCHASE_ORDER", 2, "PO-1")));
        var answer = guard.compose("{\"intro\":\"查詢結果整理如下。\",\"factIndexes\":[0]}",
                List.of(inventory, po));
        assertThat(answer.answerSource()).isEqualTo("BACKEND_FACTS");
        assertThat(answer.answer()).contains("100 KG", "200 TWD");
    }

    private AiToolResult result(String facts) {
        return new AiToolResult(AiToolType.EXPLAIN_RECOMMENDATION, AiQueryStatus.ANSWERED, facts,
                List.of(AiSourceResponse.resource("PURCHASE_RECOMMENDATION", 1, "MAT-001")));
    }
}
