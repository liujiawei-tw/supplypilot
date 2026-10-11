package com.supplypilot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.supplypilot.ai.client.AiProviderClient;
import com.supplypilot.ai.client.AiProviderException;
import com.supplypilot.ai.client.AiProviderRequest;
import com.supplypilot.ai.dto.AiQueryStatus;
import com.supplypilot.ai.dto.AiSourceResponse;
import com.supplypilot.ai.dto.AiToolType;
import com.supplypilot.ai.tool.AiQueryPlan;
import com.supplypilot.ai.tool.AiQueryPlanner;
import com.supplypilot.ai.tool.AiToolRegistry;
import com.supplypilot.ai.tool.AiToolResult;
import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.security.CurrentUserProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AiAssistantServiceTests {
    @Mock private AiQueryPlanner planner;
    @Mock private AiToolRegistry tools;
    @Mock private AiProviderClient provider;
    @Mock private CurrentUserProvider currentUserProvider;
    private AiAssistantService service;
    private AiQueryPlan plan;
    private AiToolResult result;

    @BeforeEach
    void setUp() {
        service = new AiAssistantService(planner, tools, provider, new AiResponseGuard(), currentUserProvider);
        plan = new AiQueryPlan(AiToolType.QUERY_INVENTORY, null, "MAT-001", "庫存");
        result = new AiToolResult(AiToolType.QUERY_INVENTORY, AiQueryStatus.ANSWERED,
                "MAT-001 庫存為 200 KG。", List.of(AiSourceResponse.resource("INVENTORY", 7, "MAT-001")));
        when(currentUserProvider.getCurrentUserId()).thenReturn(11L);
        when(planner.plan("庫存")).thenReturn(plan);
        when(tools.execute(plan)).thenReturn(result);
    }

    @Test
    void preservesCurrentIdentityAndReturnsEvidence() {
        when(provider.generateAnswer(new AiProviderRequest("庫存", result.tool(), result.trustedFacts(), result.sources())))
                .thenReturn("MAT-001 庫存為 200 KG。");
        var response = service.query("庫存");
        assertThat(response.status()).isEqualTo(AiQueryStatus.ANSWERED);
        assertThat(response.tools()).containsExactly(AiToolType.QUERY_INVENTORY);
        assertThat(response.sources()).containsExactlyElementsOf(result.sources());
        verify(currentUserProvider).getCurrentUserId();
    }

    @Test
    void rejectsProviderNumbersThatContradictTrustedFacts() {
        when(provider.generateAnswer(org.mockito.ArgumentMatchers.any())).thenReturn("庫存為 999 KG。");
        assertCode(ErrorCode.AI_INVALID_RESPONSE);
    }

    @Test
    void mapsProviderFailureKindsWithoutLeakingCause() {
        doThrow(new AiProviderException(ErrorCode.AI_TIMEOUT))
                .when(provider).generateAnswer(org.mockito.ArgumentMatchers.any());
        assertCode(ErrorCode.AI_TIMEOUT);

        doThrow(new IllegalStateException("secret provider token"))
                .when(provider).generateAnswer(org.mockito.ArgumentMatchers.any());
        assertCode(ErrorCode.AI_PROVIDER_UNAVAILABLE);

        doReturn(" ").when(provider).generateAnswer(org.mockito.ArgumentMatchers.any());
        assertCode(ErrorCode.AI_INVALID_RESPONSE);
    }

    @Test
    void doesNotCallProviderForInsufficientDocumentEvidence() {
        AiToolResult insufficient = AiToolResult.insufficientEvidence(AiToolType.SEARCH_DOCUMENTS);
        when(tools.execute(plan)).thenReturn(insufficient);
        var response = service.query("庫存");
        assertThat(response.status()).isEqualTo(AiQueryStatus.INSUFFICIENT_EVIDENCE);
        assertThat(response.answer()).isNull();
        assertThat(response.sources()).isEmpty();
        verify(provider, never()).generateAnswer(org.mockito.ArgumentMatchers.any());
    }

    private void assertCode(ErrorCode code) {
        assertThatThrownBy(() -> service.query("庫存"))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(code);
    }
}
