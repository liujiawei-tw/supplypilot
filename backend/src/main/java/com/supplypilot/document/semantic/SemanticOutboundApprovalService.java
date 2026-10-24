package com.supplypilot.document.semantic;

import java.time.Clock;
import java.util.List;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.document.domain.DocumentMetadata;
import com.supplypilot.document.domain.DocumentStatus;
import com.supplypilot.document.domain.SemanticReviewStatus;
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
    private final SemanticIndexJobRepository jobs;
    private final SemanticProperties properties;
    private final CurrentUserProvider currentUser;
    private final Clock clock;
    private final OutboundDocumentPolicy policy;

    public SemanticOutboundApprovalService(DocumentMetadataRepository documents,
                                           DocumentChunkRepository chunks,
                                           SemanticIndexJobRepository jobs,
                                           SemanticProperties properties,
                                           CurrentUserProvider currentUser, Clock clock,
                                           OutboundDocumentPolicy policy) {
        this.documents = documents;
        this.chunks = chunks;
        this.jobs = jobs;
        this.properties = properties;
        this.currentUser = currentUser;
        this.clock = clock;
        this.policy = policy;
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public Review approve(long documentId) {
        return review(documentId, false);
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public Review exclude(long documentId) {
        return review(documentId, true);
    }

    private Review review(long documentId, boolean exclude) {
        DocumentMetadata document = documents.findByIdForUpdate(documentId)
                .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));
        if (document.getStatus() != DocumentStatus.READY) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        var source = chunks.findByDocumentIdOrderByChunkIndex(documentId);
        if (source.isEmpty()) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        String hash = SemanticBackfillService.sourceHash(source);
        var decision = exclude
                ? new OutboundDocumentPolicy.Decision("SKIPPED", "ADMIN_EXCLUDED", List.of())
                : policy.inspectContents(source.stream().map(chunk -> chunk.getContent()).toList());
        SemanticReviewStatus status = SemanticReviewStatus.valueOf(decision.status());
        long generation = document.reviewSemanticOutbound(hash, currentUser.getCurrentUserId(), clock.instant(),
                status, decision.reason());
        if (status == SemanticReviewStatus.APPROVED) {
            // This insert shares the MySQL approval transaction. The worker can claim it only after commit.
            jobs.enqueue(documentId, document.getVersion(), hash, properties.generationKey(), generation,
                    clock.instant());
        }
        return new Review(documentId, document.getVersion(), hash, document.getSemanticApprovedBy(),
                status, decision.reason());
    }

    public record Review(long documentId, String version, String sourceHash, long approvedBy,
                         SemanticReviewStatus status, String reason) { }
}
