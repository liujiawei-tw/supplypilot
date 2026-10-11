package com.supplypilot.document.semantic;

import java.time.Clock;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.document.domain.DocumentMetadata;
import com.supplypilot.document.domain.DocumentStatus;
import com.supplypilot.document.repository.DocumentChunkRepository;
import com.supplypilot.document.repository.DocumentMetadataRepository;
import com.supplypilot.security.CurrentUserProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SemanticOutboundApprovalService {
    private final DocumentMetadataRepository documents;
    private final DocumentChunkRepository chunks;
    private final CurrentUserProvider currentUser;
    private final Clock clock;

    public SemanticOutboundApprovalService(DocumentMetadataRepository documents,
                                           DocumentChunkRepository chunks,
                                           CurrentUserProvider currentUser, Clock clock) {
        this.documents = documents;
        this.chunks = chunks;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public Approval approve(long documentId) {
        DocumentMetadata document = documents.findByIdForUpdate(documentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));
        if (document.getStatus() != DocumentStatus.READY) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        var source = chunks.findByDocumentIdOrderByChunkIndex(documentId);
        if (source.isEmpty()) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        String hash = SemanticBackfillService.sourceHash(source);
        document.approveSemanticOutbound(hash, currentUser.getCurrentUserId(), clock.instant());
        return new Approval(documentId, document.getVersion(), hash, document.getSemanticApprovedBy());
    }

    public record Approval(long documentId, String version, String sourceHash, long approvedBy) { }
}
