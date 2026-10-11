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
    void acceptsOnlyTheExactExecutedStructuredResult() throws Exception {
        AiToolResult executed = result("目前庫存=100 KG；建議採購量=200 KG");
        assertThat(guard.verifyDirectToolResult(mapper.writeValueAsString(executed), List.of(executed)))
                .isEqualTo(executed);
    }

    @Test
    void rejectsSwappedNumbersEvenThoughBothNumbersAreTrusted() throws Exception {
        AiToolResult executed = result("目前庫存=100 KG；建議採購量=200 KG");
        AiToolResult swapped = result("目前庫存=200 KG；建議採購量=100 KG");
        assertThatThrownBy(() -> guard.verifyDirectToolResult(
                mapper.writeValueAsString(swapped), List.of(executed)))
                .isInstanceOf(AiProviderException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.AI_INVALID_RESPONSE);
    }

    @Test
    void rejectsChangedRelationshipsAndUnexecutedPayloads() throws Exception {
        AiToolResult executed = result("supplier=SUP-001；material=MAT-001；unitPrice=10 TWD；totalCost=2000 TWD");
        AiToolResult changed = result("supplier=SUP-002；material=MAT-001；unitPrice=10 USD；totalCost=2000 USD");
        assertThatThrownBy(() -> guard.verifyDirectToolResult(
                mapper.writeValueAsString(changed), List.of(executed)))
                .isInstanceOf(AiProviderException.class);
        assertThatThrownBy(() -> guard.verifyDirectToolResult(
                mapper.writeValueAsString(executed), List.of()))
                .isInstanceOf(AiProviderException.class);
    }

    private AiToolResult result(String facts) {
        return new AiToolResult(AiToolType.EXPLAIN_RECOMMENDATION, AiQueryStatus.ANSWERED, facts,
                List.of(AiSourceResponse.resource("PURCHASE_RECOMMENDATION", 1, "MAT-001")));
    }
}
