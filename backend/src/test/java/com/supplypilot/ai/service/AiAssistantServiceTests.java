package com.supplypilot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

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
import com.supplypilot.document.rag.EvidenceValidator;
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
        service = new AiAssistantService(planner, provider, currentUserProvider,
                new AiAnswerEvidencePolicy(new EvidenceValidator()));
        result = new AiToolResult(AiToolType.QUERY_INVENTORY, AiQueryStatus.ANSWERED,
                "MAT-001 庫存為 200 KG。", List.of(AiSourceResponse.resource("INVENTORY", 7, "MAT-001")));
        when(currentUserProvider.getCurrentUserId()).thenReturn(11L);
        lenient().when(planner.validate("庫存")).thenReturn("庫存");
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

    @Test
    void contractLeadTimeCannotBeAnsweredByRecommendationAlone() {
        assertContractQuestionInsufficient(List.of(tool(AiToolType.QUERY_RECOMMENDATION,
                "MAT-001 建議採購。", "PURCHASE_RECOMMENDATION")));
    }

    @Test
    void contractLeadTimeCannotBeAnsweredBySupplierRankingAlone() {
        assertContractQuestionInsufficient(List.of(tool(AiToolType.QUERY_SUPPLIER_RANKING,
                "SUP-001 排名第一。", "SUPPLIER_RANKING")));
    }

    @Test
    void rankingLeadTimeDaysIsNotContractEvidence() {
        assertContractQuestionInsufficient(List.of(tool(AiToolType.QUERY_SUPPLIER_RANKING,
                "SUP-001 的 MAT-001 leadTimeDays 為 3。", "SUPPLIER_RANKING")));
    }

    @Test
    void anotherSuppliersDocumentCannotAnswerContractLeadTime() {
        assertContractQuestionInsufficient(List.of(document("SUP-002 的 MAT-001 標準交期為 ORDERED 後 3 個曆日。", "2")));
    }

    @Test
    void recommendationAndWrongDocumentCannotAnswerContractLeadTime() {
        assertContractQuestionInsufficient(List.of(
                tool(AiToolType.QUERY_RECOMMENDATION, "MAT-001 建議採購。", "PURCHASE_RECOMMENDATION"),
                document("SUP-002 的 MAT-001 標準交期為 ORDERED 後 3 個曆日。", "2")));
    }

    @Test
    void relevantContractDocumentExcludesUnrequestedRecommendationFactsAndSources() {
        String question = "SUP-001 的 MAT-001 標準交期是多久？";
        String clause = "SUP-001 的 MAT-001 標準交期為 ORDERED 後 6 個曆日。";
        AiToolResult document = document(clause, "1");
        AiToolResult recommendation = tool(AiToolType.QUERY_RECOMMENDATION,
                "MAT-001 建議採購。", "PURCHASE_RECOMMENDATION");
        var response = queryWith(question, List.of(document, recommendation));
        assertThat(response.status()).isEqualTo(AiQueryStatus.ANSWERED);
        assertThat(response.answer()).contains("ORDERED 後 6 個曆日").doesNotContain("建議採購");
        assertThat(response.sources()).containsExactlyElementsOf(document.sources());
        assertThat(response.tools()).containsExactly(AiToolType.SEARCH_DOCUMENTS, AiToolType.QUERY_RECOMMENDATION);
    }

    @Test
    void optionalMissingRecommendationDoesNotBlockDocumentAnswerButBlocksCompoundRequest() {
        String clause = "SUP-001 的 MAT-001 標準交期為 ORDERED 後 8 個曆日。";
        AiToolResult missingRecommendation = AiToolResult.insufficientEvidence(AiToolType.QUERY_RECOMMENDATION);
        AiToolResult document = document(clause, "1");

        var documentOnly = queryWith("SUP-001 的 MAT-001 標準交期是多久？",
                List.of(missingRecommendation, document));
        assertThat(documentOnly.status()).isEqualTo(AiQueryStatus.ANSWERED);
        assertThat(documentOnly.answer()).contains("8 個曆日");
        assertThat(documentOnly.sources()).containsExactlyElementsOf(document.sources());

        var compound = queryWith("SUP-001 的 MAT-001 合約交期與採購建議為何？",
                List.of(missingRecommendation, document));
        assertThat(compound.status()).isEqualTo(AiQueryStatus.INSUFFICIENT_EVIDENCE);
        assertThat(compound.answer()).isNull();
        assertThat(compound.sources()).isEmpty();
    }

    @Test
    void relevantContractDocumentAnswersWithoutHardcodedBusinessTerm() {
        String clause = "SUP-001 的 MAT-001 標準交期為 ORDERED 後 8 個曆日。";
        var response = queryWith("SUP-001 的 MAT-001 標準交期是多久？", List.of(document(clause, "1")));
        assertThat(response.status()).isEqualTo(AiQueryStatus.ANSWERED);
        assertThat(response.answer()).contains("8 個曆日");
        assertThat(response.sources()).singleElement().satisfies(source -> {
            assertThat(source.resourceId()).isEqualTo("1");
            assertThat(source.version()).isEqualTo("1.0");
            assertThat(source.chunkIndex()).isZero();
            assertThat(source.endOffset() - source.startOffset()).isEqualTo(source.excerpt().length());
        });
    }

    @Test
    void documentWithoutValidCitationCannotAnswerContractLeadTime() {
        String clause = "SUP-001 的 MAT-001 標準交期為 ORDERED 後 6 個曆日。";
        AiToolResult missingSource = new AiToolResult(AiToolType.SEARCH_DOCUMENTS,
                AiQueryStatus.ANSWERED, clause, List.of());
        assertContractQuestionInsufficient(List.of(missingSource));
        AiSourceResponse badOffset = new AiSourceResponse("DOCUMENT", "1", "測試合約", "1.0", 1L,
                0, 0, clause.length() + 1, clause, 0.9);
        assertContractQuestionInsufficient(List.of(new AiToolResult(AiToolType.SEARCH_DOCUMENTS,
                AiQueryStatus.ANSWERED, clause, List.of(badOffset))));
    }

    @Test
    void requestedInventoryAndRecommendationRemainValidMultiToolAnswer() {
        String question = "MAT-001 的庫存與採購建議為何？";
        AiToolResult inventory = tool(AiToolType.QUERY_INVENTORY, "MAT-001 庫存為 200 KG。", "INVENTORY");
        AiToolResult recommendation = tool(AiToolType.QUERY_RECOMMENDATION,
                "MAT-001 建議採購 100 KG。", "PURCHASE_RECOMMENDATION");
        var response = queryWith(question, List.of(inventory, recommendation));
        assertThat(response.status()).isEqualTo(AiQueryStatus.ANSWERED);
        assertThat(response.answer()).contains("庫存為 200 KG", "建議採購 100 KG");
        assertThat(response.sources()).hasSize(2);
    }

    @Test
    void explanationToolCanAnswerExplicitRecommendationQuestion() {
        var response = queryWith("為什麼推薦 MAT-001？", List.of(tool(AiToolType.EXPLAIN_RECOMMENDATION,
                "MAT-001 的推薦原因已核對。", "PURCHASE_RECOMMENDATION")));
        assertThat(response.status()).isEqualTo(AiQueryStatus.ANSWERED);
    }

    private void assertContractQuestionInsufficient(List<AiToolResult> results) {
        var response = queryWith("SUP-001 的 MAT-001 標準交期是多久？", results);
        assertThat(response.status()).isEqualTo(AiQueryStatus.INSUFFICIENT_EVIDENCE);
        assertThat(response.answer()).isNull();
        assertThat(response.sources()).isEmpty();
    }

    private com.supplypilot.ai.dto.AiQueryResponse queryWith(String question, List<AiToolResult> results) {
        when(planner.validate(question)).thenReturn(question);
        when(provider.isConfigured()).thenReturn(true);
        when(provider.query(any())).thenReturn(new AiProviderResponse(results,
                results.stream().map(AiToolResult::trustedFacts).filter(java.util.Objects::nonNull)
                        .collect(java.util.stream.Collectors.joining("\n")), "MODEL_EXPLANATION", 21));
        return service.query(question);
    }

    private AiToolResult tool(AiToolType type, String fact, String resourceType) {
        return new AiToolResult(type, AiQueryStatus.ANSWERED, fact,
                List.of(AiSourceResponse.resource(resourceType, 1, "MAT-001")));
    }

    private AiToolResult document(String clause, String id) {
        return new AiToolResult(AiToolType.SEARCH_DOCUMENTS, AiQueryStatus.ANSWERED, clause,
                List.of(new AiSourceResponse("DOCUMENT", id, "測試合約", "1.0", 1L,
                        0, 0, clause.length(), clause, 0.9)));
    }

    private void assertCode(ErrorCode code) {
        assertThatThrownBy(() -> service.query("庫存"))
                .isInstanceOf(BusinessException.class).extracting("errorCode").isEqualTo(code);
    }
}
