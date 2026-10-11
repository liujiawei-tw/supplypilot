package com.supplypilot.document.rag;

import java.util.List;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.document.domain.DocumentMetadata;
import com.supplypilot.document.dto.response.DocumentQueryResponse;
import com.supplypilot.document.dto.response.DocumentQueryStatus;
import com.supplypilot.document.dto.response.DocumentSourceResponse;
import com.supplypilot.document.semantic.SemanticProperties;
import com.supplypilot.document.semantic.SemanticRetrievalService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

@Service
public class DocumentQueryService {

    private final DocumentRetrievalService retrieval;
    private final EvidenceValidator evidenceValidator;
    private final AnswerProvider answers;
    private final SemanticRetrievalService semantic;
    private final SemanticProperties semanticProperties;

    public DocumentQueryService(
            DocumentRetrievalService retrieval,
            EvidenceValidator evidenceValidator,
            AnswerProvider answers,
            SemanticRetrievalService semantic,
            SemanticProperties semanticProperties
    ) {
        this.retrieval = retrieval;
        this.evidenceValidator = evidenceValidator;
        this.answers = answers;
        this.semantic = semantic;
        this.semanticProperties = semanticProperties;
    }

    @PreAuthorize("hasAnyRole('PROCUREMENT_STAFF', 'PROCUREMENT_MANAGER', 'ADMIN')")
    public DocumentQueryResponse query(String question) {
        String normalizedQuestion = question.trim();
        List<RetrievedChunk> evidence = semanticProperties.readEnabled()
                ? semantic.retrieve(normalizedQuestion) : retrieval.retrieve(normalizedQuestion);
        boolean sufficient = semanticProperties.readEnabled()
                ? evidenceValidator.isSemanticallySufficient(evidence, semanticProperties.similarityThreshold())
                : evidenceValidator.isSufficient(normalizedQuestion, evidence);
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
        DocumentMetadata document = chunk.getDocument();
        return new DocumentSourceResponse(
                document.getId(), document.getDocumentType(), document.getTitle(), document.getVersion(),
                document.getSupplier() == null ? null : document.getSupplier().getId(), chunk.getChunkIndex(),
                chunk.getStartOffset(), chunk.getEndOffset(), chunk.getContent(), result.score());
    }
}
