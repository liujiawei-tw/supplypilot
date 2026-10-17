package com.supplypilot.ai.service;

import java.util.List;
import java.util.Set;

import com.supplypilot.ai.dto.AiQueryStatus;
import com.supplypilot.ai.dto.AiSourceResponse;
import com.supplypilot.ai.dto.AiToolType;
import com.supplypilot.ai.tool.AiQuestionIntent;
import com.supplypilot.ai.tool.AiToolResult;
import com.supplypilot.document.rag.EvidenceValidator;
import org.springframework.stereotype.Component;

/** Checks whether a trusted tool result addresses the question, beyond merely having run successfully. */
@Component
public class AiAnswerEvidencePolicy {
    private final EvidenceValidator evidenceValidator;

    public AiAnswerEvidencePolicy(EvidenceValidator evidenceValidator) {
        this.evidenceValidator = evidenceValidator;
    }

    public List<AiToolResult> supportingResults(String question, List<AiToolResult> executions) {
        Set<AiToolType> requested = AiQuestionIntent.requestedTools(question);
        if (requested.isEmpty()) {
            return executions.stream().filter(this::answered).toList();
        }
        List<AiToolResult> supporting = executions.stream()
                .filter(this::answered)
                .filter(result -> requested.stream().anyMatch(tool -> AiQuestionIntent.satisfies(tool, result.tool())))
                .filter(result -> result.tool() != AiToolType.SEARCH_DOCUMENTS || supportedDocument(question, result))
                .toList();
        // A compound request is not complete when only one of its explicitly requested facts is present.
        if (requested.stream().anyMatch(tool -> supporting.stream()
                .noneMatch(result -> AiQuestionIntent.satisfies(tool, result.tool())))) {
            return List.of();
        }
        return supporting;
    }

    private boolean answered(AiToolResult result) {
        return result != null && result.status() == AiQueryStatus.ANSWERED
                && result.trustedFacts() != null && !result.trustedFacts().isBlank()
                && result.sources() != null;
    }

    private boolean supportedDocument(String question, AiToolResult result) {
        return !result.sources().isEmpty() && result.sources().stream()
                .allMatch(source -> validDocumentSource(question, source));
    }

    private boolean validDocumentSource(String question, AiSourceResponse source) {
        return source != null && "DOCUMENT".equals(source.resourceType())
                && source.resourceId() != null && !source.resourceId().isBlank()
                && source.version() != null && !source.version().isBlank()
                && source.chunkIndex() != null && source.chunkIndex() >= 0
                && source.startOffset() != null && source.startOffset() >= 0
                && source.endOffset() != null && source.endOffset() > source.startOffset()
                && source.excerpt() != null
                && source.excerpt().length() == source.endOffset() - source.startOffset()
                && !evidenceValidator.supportedExcerpt(question, source.excerpt()).isBlank();
    }
}
