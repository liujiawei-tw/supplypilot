package com.supplypilot.ai.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.supplypilot.ai.dto.AiQueryStatus;
import com.supplypilot.ai.dto.AiSourceResponse;
import com.supplypilot.ai.dto.AiToolType;
import com.supplypilot.ai.tool.AiToolResult;
import com.supplypilot.document.rag.EvidenceValidator;
import org.junit.jupiter.api.Test;

class AiAnswerEvidencePolicyTests {
    private final AiAnswerEvidencePolicy policy = new AiAnswerEvidencePolicy(new EvidenceValidator());

    @Test
    void acceptsSearchDocumentWhenFactScopedExcerptExactlyMatchesItsSourceOffsets() {
        String question = "SUP-001 的 MAT-001 標準交期是多久？";
        String source = "其他物料規格不應列為這個來源。\nSUP-001 的 MAT-001 標準交期為 5 個日曆日。";
        String excerpt = "SUP-001 的 MAT-001 標準交期為 5 個日曆日。";
        int startOffset = source.indexOf(excerpt);
        AiSourceResponse sourceResponse = new AiSourceResponse("DOCUMENT", "27", "供應商合約", "DOC-027",
                "1.0", 8L, 0, startOffset, startOffset + excerpt.length(), excerpt, 0.87);
        AiToolResult searchResult = new AiToolResult(AiToolType.SEARCH_DOCUMENTS, AiQueryStatus.ANSWERED,
                excerpt, List.of(sourceResponse));

        assertThat(excerpt.length()).isEqualTo(sourceResponse.endOffset() - sourceResponse.startOffset());
        assertThat(policy.supportingResults(question, List.of(searchResult))).containsExactly(searchResult);
    }

    @Test
    void acceptsSupplierIdentityAndRequestedMaterialFactAsSeparateExcerptsFromOneDocument() {
        String question = "SUP-001 的 MAT-001 標準交期是多久？";
        String supplierClause = "本合約的供應商是 SUP-001。";
        String deliveryClause = "MAT-001 標準交期為 5 個日曆日。";
        AiSourceResponse supplierSource = source("27", "DOC-027", "1.0", 8,
                supplierClause, 0);
        AiSourceResponse factSource = source("27", "DOC-027", "1.0", 9,
                deliveryClause, supplierClause.length() + 1);
        AiToolResult searchResult = new AiToolResult(AiToolType.SEARCH_DOCUMENTS, AiQueryStatus.ANSWERED,
                deliveryClause, List.of(supplierSource, factSource));

        assertThat(policy.supportingResults(question, List.of(searchResult))).containsExactly(searchResult);
        assertThat(searchResult.sources()).containsExactly(supplierSource, factSource);
        assertThat(searchResult.sources()).allSatisfy(source ->
                assertThat(source.endOffset() - source.startOffset()).isEqualTo(source.excerpt().length()));
    }

    @Test
    void doesNotCombineDifferentMaterialOrDifferentSourceExcerptsToSupportSearch() {
        String question = "SUP-001 的 MAT-001 標準交期是多久？";
        String supplierClause = "本合約的供應商是 SUP-001。";
        String wrongMaterialClause = "MAT-002 標準交期為 5 個日曆日。";
        AiSourceResponse supplierSource = source("27", "DOC-027", "1.0", 8,
                supplierClause, 0);
        AiSourceResponse wrongMaterialSource = source("27", "DOC-027", "1.0", 8,
                wrongMaterialClause, supplierClause.length() + 1);
        AiToolResult wrongMaterialResult = new AiToolResult(AiToolType.SEARCH_DOCUMENTS,
                AiQueryStatus.ANSWERED, wrongMaterialClause, List.of(supplierSource, wrongMaterialSource));

        String requestedMaterialClause = "MAT-001 標準交期為 5 個日曆日。";
        AiSourceResponse separateDocumentFact = source("28", "DOC-028", "1.0", 8,
                requestedMaterialClause, 0);
        AiToolResult crossDocumentResult = new AiToolResult(AiToolType.SEARCH_DOCUMENTS,
                AiQueryStatus.ANSWERED, requestedMaterialClause, List.of(supplierSource, separateDocumentFact));

        assertThat(policy.supportingResults(question, List.of(wrongMaterialResult))).isEmpty();
        assertThat(policy.supportingResults(question, List.of(crossDocumentResult))).isEmpty();
    }

    private AiSourceResponse source(String resourceId, String documentKey, String version, int chunkIndex,
            String excerpt, int startOffset) {
        return new AiSourceResponse("DOCUMENT", resourceId, "供應商合約", documentKey, version,
                8L, chunkIndex, startOffset, startOffset + excerpt.length(), excerpt, 0.87);
    }
}
