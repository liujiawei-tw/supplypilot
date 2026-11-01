package com.supplypilot.document.rag;

import com.supplypilot.document.dto.response.DocumentQueryResponse;
import com.supplypilot.document.dto.response.DocumentQueryStatus;
import com.supplypilot.document.dto.response.DocumentSourceResponse;
import com.supplypilot.document.semantic.OutboundDocumentPolicy;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

@Service
public class DocumentQueryService {

    private final SpringAiRagAnswerPipeline pipeline;
    private final OutboundDocumentPolicy policy;

    public DocumentQueryService(
            SpringAiRagAnswerPipeline pipeline,
            OutboundDocumentPolicy policy
    ) {
        this.pipeline = pipeline;
        this.policy = policy;
    }

    @PreAuthorize("hasAnyRole('PROCUREMENT_STAFF', 'PROCUREMENT_MANAGER', 'ADMIN')")
    public DocumentQueryResponse query(String question) {
        String normalizedQuestion = policy.safeQuery(question.trim());
        SpringAiRagAnswerPipeline.QueryResult result = pipeline.answer(normalizedQuestion);
        if (!result.answered()) return DocumentQueryResponse.insufficientEvidence();
        var sources = result.evidence().stream().map(evidence -> new DocumentSourceResponse(
                evidence.documentId(), evidence.documentKey(), evidence.documentType(), evidence.title(),
                evidence.version(), evidence.supplierId(), evidence.supplierCode(), evidence.supplierName(),
                evidence.chunkIndex(), evidence.startOffset(), evidence.endOffset(), evidence.excerpt(),
                evidence.relevanceScore())).toList();
        return new DocumentQueryResponse(DocumentQueryStatus.ANSWERED, result.answer(), sources);
    }
}
