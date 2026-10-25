package com.supplypilot.document.rag;

import java.util.List;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.document.dto.response.DocumentQueryResponse;
import com.supplypilot.document.dto.response.DocumentQueryStatus;
import com.supplypilot.document.dto.response.DocumentSourceResponse;
import com.supplypilot.document.semantic.OutboundDocumentPolicy;
import com.supplypilot.document.semantic.SemanticProperties;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

@Service
public class DocumentQueryService {

    private final HybridRetrievalService retrieval;
    private final EvidenceValidator evidenceValidator;
    private final AnswerProvider answers;
    private final SemanticProperties semanticProperties;
    private final OutboundDocumentPolicy policy;

    public DocumentQueryService(
            HybridRetrievalService retrieval,
            EvidenceValidator evidenceValidator,
            AnswerProvider answers,
            SemanticProperties semanticProperties,
            OutboundDocumentPolicy policy
    ) {
        this.retrieval = retrieval;
        this.evidenceValidator = evidenceValidator;
        this.answers = answers;
        this.semanticProperties = semanticProperties;
        this.policy = policy;
    }

    @PreAuthorize("hasAnyRole('PROCUREMENT_STAFF', 'PROCUREMENT_MANAGER', 'ADMIN')")
    public DocumentQueryResponse query(String question) {
        String normalizedQuestion = policy.safeQuery(question.trim());
        List<RetrievedChunk> evidence = retrieval.retrieve(normalizedQuestion);
        evidence = evidenceValidator.supportingEvidence(
                normalizedQuestion, evidence, semanticProperties.similarityThreshold());
        boolean sufficient = !evidence.isEmpty();
        if (!sufficient) {
            return DocumentQueryResponse.insufficientEvidence();
        }
        final String answer;
        try {
            answer = answers.answer(normalizedQuestion, evidence);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
        }
        if (answer == null || answer.isBlank()) {
            throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
        }
        List<DocumentSourceResponse> sources = evidence.stream().map(this::source).toList();
        return new DocumentQueryResponse(DocumentQueryStatus.ANSWERED, answer, sources);
    }

    private DocumentSourceResponse source(RetrievedChunk result) {
        var chunk = result.chunk();
        var document = chunk.getDocument();
        return new DocumentSourceResponse(
                document.getId(), document.getDocumentType(), document.getTitle(), document.getVersion(),
                document.getSupplier() == null ? null : document.getSupplier().getId(),
                document.getSupplier() == null ? null : document.getSupplier().getCode(),
                document.getSupplier() == null ? null : document.getSupplier().getName(), chunk.getChunkIndex(),
                chunk.getStartOffset(), chunk.getEndOffset(), chunk.getContent(), result.score());
    }
}
