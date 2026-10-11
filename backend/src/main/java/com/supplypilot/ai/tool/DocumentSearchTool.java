package com.supplypilot.ai.tool;

import java.util.List;

import com.supplypilot.ai.dto.AiQueryStatus;
import com.supplypilot.ai.dto.AiSourceResponse;
import com.supplypilot.ai.dto.AiToolType;
import com.supplypilot.document.dto.response.DocumentQueryStatus;
import com.supplypilot.document.rag.DocumentQueryService;
import org.springframework.stereotype.Component;

@Component
public class DocumentSearchTool {
    private final DocumentQueryService documents;

    public DocumentSearchTool(DocumentQueryService documents) {
        this.documents = documents;
    }

    public AiToolResult execute(AiQueryPlan plan) {
        var result = documents.query(plan.question());
        if (result.status() == DocumentQueryStatus.INSUFFICIENT_EVIDENCE) {
            return AiToolResult.insufficientEvidence(AiToolType.SEARCH_DOCUMENTS);
        }
        List<AiSourceResponse> sources = result.sources().stream()
                .map(source -> new AiSourceResponse(
                        "DOCUMENT", String.valueOf(source.documentId()), source.title(), source.version(),
                        source.supplierId(), source.chunkIndex(), source.startOffset(), source.endOffset(),
                        source.excerpt(), source.relevanceScore()))
                .toList();
        return new AiToolResult(AiToolType.SEARCH_DOCUMENTS, AiQueryStatus.ANSWERED,
                result.answer(), sources);
    }
}
