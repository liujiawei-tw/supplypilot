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
}
