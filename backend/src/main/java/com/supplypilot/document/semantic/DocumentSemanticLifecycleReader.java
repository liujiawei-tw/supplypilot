package com.supplypilot.document.semantic;

import java.util.ArrayList;
import java.util.List;

import com.supplypilot.demo.DemoCorpusRagEligibility;
import com.supplypilot.document.domain.DocumentMetadata;
import com.supplypilot.document.domain.DocumentStatus;
import com.supplypilot.document.domain.SemanticReviewStatus;
import com.supplypilot.document.dto.response.DocumentSemanticIndexResponse;
import com.supplypilot.document.repository.DocumentChunkRepository;
import com.supplypilot.document.repository.DocumentMetadataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class DocumentSemanticLifecycleReader {
    private static final Logger logger = LoggerFactory.getLogger(DocumentSemanticLifecycleReader.class);

    private final DocumentChunkRepository chunks;
    private final SemanticIndexJobRepository jobs;
    private final SemanticStore store;
    private final SemanticProperties properties;
    private final OutboundDocumentPolicy policy;
    private final DocumentMetadataRepository documents;
    private final TransactionTemplate primaryTransactions;
    private final DemoCorpusRagEligibility demoEligibility;

    public DocumentSemanticLifecycleReader(DocumentChunkRepository chunks, SemanticIndexJobRepository jobs,
            SemanticStore store, SemanticProperties properties, OutboundDocumentPolicy policy,
            DocumentMetadataRepository documents, PlatformTransactionManager transactionManager) {
        this(chunks, jobs, store, properties, policy, documents, transactionManager,
                DemoCorpusRagEligibility.manualUploadsOnly());
    }

    @Autowired
    public DocumentSemanticLifecycleReader(DocumentChunkRepository chunks, SemanticIndexJobRepository jobs,
            SemanticStore store, SemanticProperties properties, OutboundDocumentPolicy policy,
            DocumentMetadataRepository documents, PlatformTransactionManager transactionManager,
            DemoCorpusRagEligibility demoEligibility) {
        this.chunks = chunks;
        this.jobs = jobs;
        this.store = store;
        this.properties = properties;
        this.policy = policy;
        this.documents = documents;
        this.primaryTransactions = new TransactionTemplate(transactionManager);
        this.demoEligibility = demoEligibility;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public DocumentSemanticIndexResponse read(DocumentMetadata document) {
        return readAll(List.of(document)).getFirst();
    }

    /** Reads a coherent MySQL source snapshot, then verifies pgvector after that transaction ends. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<DocumentSemanticIndexResponse> readAll(List<DocumentMetadata> page) {
        List<LifecycleSnapshot> snapshots = primaryTransactions.execute(transaction -> page.stream()
                .map(document -> readSnapshot(document.getId())).toList());
        if (snapshots == null) throw new IllegalStateException("Semantic lifecycle snapshot was not loaded");

        List<DocumentSemanticIndexResponse> result = new ArrayList<>(snapshots.size());
        boolean publicationStoreUnavailable = false;
        for (LifecycleSnapshot snapshot : snapshots) {
            if (snapshot.response() != null) {
                result.add(snapshot.response());
                continue;
            }
            if (publicationStoreUnavailable) {
                result.add(unavailable(snapshot));
                continue;
            }
            try {
                SemanticStore.PublicationCheck publication = store.inspectPublication(snapshot.documentId(),
                        snapshot.version(), properties.generationKey(), snapshot.sourceHash(),
                        snapshot.documentGeneration(), snapshot.expectedSource());
                if (publication.current()) {
                    result.add(response("INDEXED", snapshot.jobId(), snapshot.sourceHash(),
                            snapshot.expectedChunks(), publication.publishedChunks(), null,
                            publication.state().publicationGeneration()));
                } else {
                    result.add(response("PENDING", snapshot.jobId(), snapshot.sourceHash(),
                            snapshot.expectedChunks(), publication.publishedChunks(),
                            publication.state() == null ? "PUBLISHED_INDEX_MISSING" : "PUBLISHED_INDEX_STALE",
                            publication.state() == null ? null : publication.state().publicationGeneration()));
                }
            } catch (DataAccessException unavailable) {
                publicationStoreUnavailable = true;
                logger.warn("Semantic publication check unavailable: documentId={}, exceptionType={}",
                        snapshot.documentId(), unavailable.getClass().getSimpleName());
                result.add(unavailable(snapshot));
            }
        }
        return List.copyOf(result);
    }

    private LifecycleSnapshot readSnapshot(long documentId) {
        DocumentMetadata document = documents.findForSemanticRead(documentId).orElse(null);
        if (document == null || document.getStatus() != DocumentStatus.READY) {
            return immediate(response("NOT_READY", null, null, 0, 0, null));
        }
        List<com.supplypilot.document.domain.DocumentChunk> source =
                chunks.findByDocumentIdOrderByChunkIndex(documentId);
        if (source.isEmpty()) return immediate(response("SOURCE_MISSING", null, null, 0, 0,
                "SOURCE_CHUNKS_MISSING"));
        String hash = SemanticBackfillService.sourceHash(source);
        if (document.getSemanticReviewStatus() == null) {
            return immediate(response("NOT_REVIEWED", null, hash, source.size(), 0, null));
        }
        if (document.getSemanticReviewStatus() == SemanticReviewStatus.SKIPPED) {
            return immediate(response("EXCLUDED", null, hash, source.size(), 0,
                    document.getSemanticReviewReason()));
        }
        if (document.getSemanticReviewStatus() == SemanticReviewStatus.REQUIRES_REVIEW) {
            return immediate(response("REVIEW_REQUIRED", null, hash, source.size(), 0,
                    document.getSemanticReviewReason()));
        }
        if (document.getSemanticReviewStatus() != SemanticReviewStatus.APPROVED
                || !hash.equals(document.getSemanticApprovedSourceHash())
                || !document.getVersion().equals(document.getSemanticReviewVersion())) {
            return immediate(response("STALE_APPROVAL", null, hash, source.size(), 0,
                    "SOURCE_OR_VERSION_CHANGED"));
        }
        if (!demoEligibility.isEligible(document)) {
            return immediate(response("EXCLUDED", null, hash, source.size(), 0,
                    "DEMO_CORPUS_VERSION_NOT_CURRENT"));
        }
        OutboundDocumentPolicy.Decision decision = demoEligibility.inspectSource(document, source, hash,
                document.getSemanticIndexGeneration(), policy);
        if (!"APPROVED".equals(decision.status())) {
            return immediate(response("POLICY_RECHECK_REQUIRED", null, hash, source.size(), 0,
                    decision.reason()));
        }
        var job = jobs.findCurrent(documentId, document.getVersion(), hash,
                properties.generationKey(), document.getSemanticIndexGeneration()).orElse(null);
        if (job == null) return immediate(response("PENDING", null, hash, source.size(), 0, "INDEX_JOB_MISSING"));
        if (job.status() != SemanticIndexJobStatus.SUCCEEDED) {
            return immediate(response(job.status().name(), job.id(), hash, source.size(), 0, job.failureCode()));
        }

        List<SemanticStore.SourceChunk> expected = SemanticBackfillService.sourceProof(source, decision.safeChunks());
        return new LifecycleSnapshot(null, documentId, document.getVersion(), hash, source.size(),
                document.getSemanticIndexGeneration(), job.id(), expected);
    }

    private LifecycleSnapshot immediate(DocumentSemanticIndexResponse response) {
        return new LifecycleSnapshot(response, null, null, response.sourceHash(), response.expectedChunks(),
                0, response.jobId(), List.of());
    }

    private DocumentSemanticIndexResponse unavailable(LifecycleSnapshot snapshot) {
        return response("UNAVAILABLE", snapshot.jobId(), snapshot.sourceHash(),
                snapshot.expectedChunks(), 0, "INDEX_STORE_UNAVAILABLE");
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

    private record LifecycleSnapshot(DocumentSemanticIndexResponse response, Long documentId, String version,
            String sourceHash, int expectedChunks, long documentGeneration, Long jobId,
            List<SemanticStore.SourceChunk> expectedSource) { }
}
