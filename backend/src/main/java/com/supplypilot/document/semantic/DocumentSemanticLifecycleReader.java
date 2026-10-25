package com.supplypilot.document.semantic;

import java.util.List;

import com.supplypilot.document.domain.DocumentMetadata;
import com.supplypilot.document.domain.DocumentStatus;
import com.supplypilot.document.domain.SemanticReviewStatus;
import com.supplypilot.document.dto.response.DocumentSemanticIndexResponse;
import com.supplypilot.document.repository.DocumentChunkRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentSemanticLifecycleReader {
    private final DocumentChunkRepository chunks;
    private final SemanticIndexJobRepository jobs;
    private final SemanticStore store;
    private final SemanticProperties properties;
    private final OutboundDocumentPolicy policy;

    public DocumentSemanticLifecycleReader(DocumentChunkRepository chunks, SemanticIndexJobRepository jobs,
                                           SemanticStore store, SemanticProperties properties,
                                           OutboundDocumentPolicy policy) {
        this.chunks = chunks;
        this.jobs = jobs;
        this.store = store;
        this.properties = properties;
        this.policy = policy;
    }

    @Transactional(readOnly = true)
    public DocumentSemanticIndexResponse read(DocumentMetadata document) {
        if (document.getStatus() != DocumentStatus.READY) {
            return response("NOT_READY", null, null, 0, 0, null);
        }
        List<com.supplypilot.document.domain.DocumentChunk> source =
                chunks.findByDocumentIdOrderByChunkIndex(document.getId());
        if (source.isEmpty()) return response("SOURCE_MISSING", null, null, 0, 0, "SOURCE_CHUNKS_MISSING");
        String hash = SemanticBackfillService.sourceHash(source);
        if (document.getSemanticReviewStatus() == null) {
            return response("NOT_REVIEWED", null, hash, source.size(), 0, null);
        }
        if (document.getSemanticReviewStatus() == SemanticReviewStatus.SKIPPED) {
            return response("EXCLUDED", null, hash, source.size(), 0, document.getSemanticReviewReason());
        }
        if (document.getSemanticReviewStatus() == SemanticReviewStatus.REQUIRES_REVIEW) {
            return response("REVIEW_REQUIRED", null, hash, source.size(), 0, document.getSemanticReviewReason());
        }
        if (document.getSemanticReviewStatus() != SemanticReviewStatus.APPROVED
                || !hash.equals(document.getSemanticApprovedSourceHash())
                || !document.getVersion().equals(document.getSemanticReviewVersion())) {
            return response("STALE_APPROVAL", null, hash, source.size(), 0, "SOURCE_OR_VERSION_CHANGED");
        }
        OutboundDocumentPolicy.Decision decision = policy.inspect(document,
                source.stream().map(com.supplypilot.document.domain.DocumentChunk::getContent).toList(), hash);
        if (!"APPROVED".equals(decision.status())) {
            return response("POLICY_RECHECK_REQUIRED", null, hash, source.size(), 0, decision.reason());
        }
        var job = jobs.findCurrent(document.getId(), document.getVersion(), hash,
                properties.generationKey(), document.getSemanticIndexGeneration()).orElse(null);
        if (job == null) return response("PENDING", null, hash, source.size(), 0, "INDEX_JOB_MISSING");
        if (job.status() != SemanticIndexJobStatus.SUCCEEDED) {
            return response(job.status().name(), job.id(), hash, source.size(), 0, job.failureCode());
        }

        List<SemanticStore.SourceChunk> expected = SemanticBackfillService.sourceProof(source, decision.safeChunks());
        try {
            SemanticStore.PublicationCheck publication = store.inspectPublication(document.getId(),
                    document.getVersion(), properties.generationKey(), hash,
                    document.getSemanticIndexGeneration(), expected);
            if (publication.current()) {
                return response("INDEXED", job.id(), hash, source.size(),
                        publication.publishedChunks(), null, publication.state().publicationGeneration());
            }
            return response("PENDING", job.id(), hash, source.size(), publication.publishedChunks(),
                    publication.state() == null ? "PUBLISHED_INDEX_MISSING" : "PUBLISHED_INDEX_STALE",
                    publication.state() == null ? null : publication.state().publicationGeneration());
        } catch (RuntimeException unavailable) {
            return response("UNAVAILABLE", job.id(), hash, source.size(), 0, "INDEX_STORE_UNAVAILABLE");
        }
    }

    private DocumentSemanticIndexResponse response(String status, Long jobId, String hash,
                                                   int expected, int published, String failureCode) {
        return response(status, jobId, hash, expected, published, failureCode, null);
    }

    private DocumentSemanticIndexResponse response(String status, Long jobId, String hash,
                                                   int expected, int published, String failureCode,
                                                   Long publicationGeneration) {
        return new DocumentSemanticIndexResponse(status, jobId, hash, expected, published, failureCode,
                properties.generationKey(), publicationGeneration);
    }
}

