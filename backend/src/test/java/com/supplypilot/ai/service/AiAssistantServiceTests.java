package com.supplypilot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import com.supplypilot.ai.client.AiProviderClient;
import com.supplypilot.ai.client.AiProviderException;
import com.supplypilot.ai.client.AiProviderRequest;
import com.supplypilot.ai.client.AiProviderResponse;
import com.supplypilot.ai.dto.AiQueryStatus;
import com.supplypilot.ai.dto.AiSourceResponse;
import com.supplypilot.ai.dto.AiToolType;
import com.supplypilot.ai.tool.AiQueryPlanner;
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
    @Mock private AiProviderClient provider;
    @Mock private CurrentUserProvider currentUserProvider;
    private AiAssistantService service;
    private AiToolResult result;

    @BeforeEach
    void setUp() {
        service = new AiAssistantService(planner, provider, currentUserProvider);
        result = new AiToolResult(AiToolType.QUERY_INVENTORY, AiQueryStatus.ANSWERED,
                "MAT-001 庫存為 200 KG。", List.of(AiSourceResponse.resource("INVENTORY", 7, "MAT-001")));
        when(currentUserProvider.getCurrentUserId()).thenReturn(11L);
        when(planner.validate("庫存")).thenReturn("庫存");
    }

    @Test
    void preservesCurrentIdentityAndReturnsOnlyVerifiedToolFacts() {
        when(provider.isConfigured()).thenReturn(true);
        when(provider.query(any())).thenReturn(new AiProviderResponse(
                List.of(result), "查詢結果整理如下。\n" + result.trustedFacts(), "MODEL_EXPLANATION", 21));
        var response = service.query("庫存");
        assertThat(response.status()).isEqualTo(AiQueryStatus.ANSWERED);
        assertThat(response.answer()).contains(result.trustedFacts());
        assertThat(response.answerSource()).isEqualTo("MODEL_EXPLANATION");
        assertThat(response.tools()).containsExactly(AiToolType.QUERY_INVENTORY);
        assertThat(response.sources()).containsExactlyElementsOf(result.sources());
        verify(currentUserProvider).getCurrentUserId();
        verify(planner).validate("庫存");
        verify(provider).query(new AiProviderRequest("庫存"));
    }

    @Test
    void mapsProviderFailureKindsWithoutLeakingCause() {
        when(provider.isConfigured()).thenReturn(true);
        when(provider.query(any())).thenThrow(new AiProviderException(ErrorCode.AI_TIMEOUT));
        assertCode(ErrorCode.AI_TIMEOUT);

        reset(provider);
        when(provider.isConfigured()).thenReturn(true);
        when(provider.query(any())).thenThrow(new IllegalStateException("secret provider token"));
        assertCode(ErrorCode.AI_PROVIDER_UNAVAILABLE);
    }

    @Test
    void preservesInsufficientEvidenceWithoutInventingAnAnswer() {
        when(provider.isConfigured()).thenReturn(true);
        AiToolResult insufficient = AiToolResult.insufficientEvidence(AiToolType.SEARCH_DOCUMENTS);
        when(provider.query(any())).thenReturn(new AiProviderResponse(List.of(insufficient), null, null, 12));
        var response = service.query("庫存");
        assertThat(response.status()).isEqualTo(AiQueryStatus.INSUFFICIENT_EVIDENCE);
        assertThat(response.answer()).isNull();
        assertThat(response.sources()).isEmpty();
    }

    @Test
    void validationRunsBeforeProvider() {
        when(planner.validate("庫存")).thenThrow(new BusinessException(ErrorCode.ACCESS_DENIED));
        assertCode(ErrorCode.ACCESS_DENIED);
        verify(provider, never()).query(any());
    }

    @Test
    void disabledProviderPrecedesClarificationWithoutCallingModel() {
        assertCode(ErrorCode.AI_PROVIDER_UNAVAILABLE);
        verify(provider, never()).query(any());
        verify(planner, never()).missingIdentifier(any());
    }

    @Test
    void missingIdentifierReturnsClarificationWithoutProviderCall() {
        when(provider.isConfigured()).thenReturn(true);
        when(planner.missingIdentifier("庫存")).thenReturn("請補充 Material Code");
        var response = service.query("庫存");
        assertThat(response.status()).isEqualTo(AiQueryStatus.NEEDS_CLARIFICATION);
        assertThat(response.answer()).contains("Material Code");
        assertThat(response.tools()).isEmpty();
        verify(provider, never()).query(any());
    }

    private void assertCode(ErrorCode code) {
        assertThatThrownBy(() -> service.query("庫存"))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(code);
    }
}
