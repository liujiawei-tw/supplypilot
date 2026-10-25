package com.supplypilot.document.semantic;

import java.time.Clock;
import java.util.List;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.demo.DemoCorpusRagEligibility;
import com.supplypilot.document.domain.DocumentMetadata;
import com.supplypilot.document.domain.DocumentStatus;
import com.supplypilot.document.domain.SemanticReviewStatus;
import com.supplypilot.document.repository.DocumentChunkRepository;
import com.supplypilot.document.repository.DocumentMetadataRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SemanticIndexJobService {
    private final SemanticIndexJobRepository jobs;
    private final DocumentMetadataRepository documents;
    private final DocumentChunkRepository chunks;
    private final OutboundDocumentPolicy policy;
    private final SemanticProperties semanticProperties;
    private final SemanticIndexingProperties indexingProperties;
    private final Clock clock;
    private final DemoCorpusRagEligibility demoEligibility;

    public SemanticIndexJobService(SemanticIndexJobRepository jobs, DocumentMetadataRepository documents,
                                   DocumentChunkRepository chunks, OutboundDocumentPolicy policy,
                                   SemanticProperties semanticProperties,
                                   SemanticIndexingProperties indexingProperties, Clock clock) {
        this(jobs, documents, chunks, policy, semanticProperties, indexingProperties, clock,
                DemoCorpusRagEligibility.manualUploadsOnly());
    }

    @Autowired
    public SemanticIndexJobService(SemanticIndexJobRepository jobs, DocumentMetadataRepository documents,
                                   DocumentChunkRepository chunks, OutboundDocumentPolicy policy,
                                   SemanticProperties semanticProperties,
                                   SemanticIndexingProperties indexingProperties, Clock clock,
                                   DemoCorpusRagEligibility demoEligibility) {
        this.jobs = jobs;
        this.documents = documents;
        this.chunks = chunks;
        this.policy = policy;
        this.semanticProperties = semanticProperties;
        this.indexingProperties = indexingProperties;
        this.clock = clock;
        this.demoEligibility = demoEligibility;
    }

    @Transactional(readOnly = true)
    @PreAuthorize("hasRole('ADMIN')")
    public List<SemanticIndexJobResponse> latest(int limit) {
        return jobs.latest(limit).stream()
                .map(job -> SemanticIndexJobResponse.from(job, indexingProperties.maxAttempts())).toList();
    }

    @Transactional
    @PreAuthorize("hasRole('ADMIN')")
    public SemanticIndexJobResponse retry(long jobId) {
        SemanticIndexJob initial = jobs.findById(jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));
        DocumentMetadata document = documents.findByIdForUpdate(initial.documentId())
                .orElseThrow(() -> new BusinessException(ErrorCode.DOCUMENT_NOT_FOUND));
        SemanticIndexJob job = jobs.findByIdForUpdate(jobId)
                .orElseThrow(() -> new BusinessException(ErrorCode.VALIDATION_FAILED));

        if (job.status() == SemanticIndexJobStatus.QUEUED
                || job.status() == SemanticIndexJobStatus.PROCESSING
                || job.status() == SemanticIndexJobStatus.RETRY_WAIT) {
            return SemanticIndexJobResponse.from(job, indexingProperties.maxAttempts());
        }
        if (job.status() != SemanticIndexJobStatus.FAILED) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        if (document.getStatus() != DocumentStatus.READY
                || !document.getVersion().equals(job.version())
                || document.getSemanticIndexGeneration() != job.documentGeneration()
                || !semanticProperties.generationKey().equals(job.generationKey())
                || !demoEligibility.isEligible(document)) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        List<com.supplypilot.document.domain.DocumentChunk> source =
                chunks.findByDocumentIdOrderByChunkIndex(job.documentId());
        String hash = SemanticBackfillService.sourceHash(source);
        var decision = demoEligibility.inspectSource(document, source, hash,
                document.getSemanticIndexGeneration(), policy);
        if (!hash.equals(job.sourceHash()) || !"APPROVED".equals(decision.status())
                || document.getSemanticReviewStatus() != SemanticReviewStatus.APPROVED) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        jobs.queueManualRetry(job.id(), clock.instant());
        SemanticIndexJob queued = jobs.findById(job.id()).orElseThrow();
        return SemanticIndexJobResponse.from(queued, indexingProperties.maxAttempts());
    }
}
