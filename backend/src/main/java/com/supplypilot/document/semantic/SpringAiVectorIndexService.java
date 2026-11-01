package com.supplypilot.document.semantic;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import com.supplypilot.demo.DemoCorpusRagEligibility;
import com.supplypilot.document.domain.DocumentChunk;
import com.supplypilot.document.domain.DocumentMetadata;
import com.supplypilot.document.domain.DocumentStatus;
import com.supplypilot.document.domain.SemanticReviewStatus;
import com.supplypilot.document.dto.response.DocumentSemanticIndexResponse;
import com.supplypilot.document.repository.DocumentChunkRepository;
import com.supplypilot.document.repository.DocumentMetadataRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/** Writes safe approved chunks through Spring AI's PgVectorStore and publishes only complete generations. */
@Service
public class SpringAiVectorIndexService {
    private static final Logger logger = LoggerFactory.getLogger(SpringAiVectorIndexService.class);
    private static final int WRITE_BATCH_SIZE = 50;

    private final ObjectProvider<PgVectorStore> vectorStores;
    private final SemanticStore registry;
    private final SemanticProperties properties;
    private final DocumentMetadataRepository documents;
    private final DocumentChunkRepository chunks;
    private final OutboundDocumentPolicy outboundPolicy;
    private final DemoCorpusRagEligibility demoEligibility;
    private final AtomicLong cleanupCursor = new AtomicLong();

    public SpringAiVectorIndexService(@Qualifier("ragWriteVectorStore") ObjectProvider<PgVectorStore> vectorStores,
            SemanticStore registry, SemanticProperties properties,
            DocumentMetadataRepository documents, DocumentChunkRepository chunks,
            OutboundDocumentPolicy outboundPolicy, DemoCorpusRagEligibility demoEligibility) {
        this.vectorStores = vectorStores;
        this.registry = registry;
        this.properties = properties;
        this.documents = documents;
        this.chunks = chunks;
        this.outboundPolicy = outboundPolicy;
        this.demoEligibility = demoEligibility;
    }

    /**
     * @return true only when the current approved source generation has a complete PgVectorStore index.
     */
    public boolean index(long documentId, String version, String sourceHash, long documentGeneration,
                         List<SemanticIndexSnapshotReader.SourceChunk> safeChunks,
                         BooleanSupplier renewAndVerifySource) {
        PgVectorStore vectorStore = vectorStores.getIfAvailable();
        if (vectorStore == null) throw new DataAccessResourceFailureException("PgVectorStore is unavailable");
        if (safeChunks.isEmpty()) throw new IllegalArgumentException("Document contains no indexable chunks");

        registry.mark(documentId, version, "PROCESSING", null, sourceHash,
                safeChunks.size(), documentGeneration);
        List<Document> indexed = toDocuments(documentId, version, sourceHash, documentGeneration, safeChunks);
        for (int start = 0; start < indexed.size(); start += WRITE_BATCH_SIZE) {
            if (!renewAndVerifySource.getAsBoolean()) {
                // IDs are deterministic per document generation, so a lease retry may be writing
                // the same IDs concurrently. Leave staged rows for generation-fenced reconciliation.
                return false;
            }
            vectorStore.add(indexed.subList(start, Math.min(start + WRITE_BATCH_SIZE, indexed.size())));
        }
        if (!renewAndVerifySource.getAsBoolean()) {
            return false;
        }

        List<SemanticStore.SafeChunk> publication = safeChunks.stream()
                .map(chunk -> new SemanticStore.SafeChunk(chunk.index(), chunk.startOffset(),
                        chunk.endOffset(), chunk.content())).toList();
        boolean published = registry.publish(documentId, version, sourceHash, documentGeneration, publication);
        if (published) deleteSupersededVectors(vectorStore, documentId);
        return published;
    }

    public boolean index(DocumentMetadata document, String sourceHash,
                         List<SemanticIndexSnapshotReader.SourceChunk> safeChunks) {
        return index(document.getId(), document.getVersion(), sourceHash,
                document.getSemanticIndexGeneration(), safeChunks,
                () -> isCurrentApproved(document.getId(), document.getVersion(), sourceHash,
                        document.getSemanticIndexGeneration(), safeChunks));
    }

    /** Reconciles a bounded set of vectors from generations older than the current MySQL fence. */
    public void cleanupStaleVectorPage() {
        PgVectorStore vectorStore;
        try {
            vectorStore = vectorStores.getIfAvailable();
        } catch (RuntimeException unavailable) {
            logger.warn("Could not resolve RAG vector store for cleanup: exceptionType={}",
                    unavailable.getClass().getSimpleName());
            return;
        }
        if (vectorStore == null) return;
        List<Long> page;
        try {
            page = registry.vectorDocumentIdsAfter(cleanupCursor.get(), 50);
        } catch (RuntimeException unavailable) {
            logger.warn("Could not enumerate RAG vectors for cleanup: exceptionType={}",
                    unavailable.getClass().getSimpleName());
            return;
        }
        if (page.isEmpty()) {
            cleanupCursor.set(0);
            return;
        }
        for (Long documentId : page) {
            try {
                reconcileDocumentVectors(vectorStore, documentId);
            } catch (RuntimeException failure) {
                logger.warn("Could not reconcile stale RAG vectors: documentId={}, exceptionType={}",
                        documentId, failure.getClass().getSimpleName());
            }
        }
        cleanupCursor.set(page.getLast());
    }

    private void reconcileDocumentVectors(PgVectorStore vectorStore, long documentId) {
        List<SemanticStore.VectorReference> vectors = registry.vectorReferencesForDocument(documentId);
        if (vectors.isEmpty()) return;
        DocumentMetadata document = documents.findForSemanticRead(documentId).orElse(null);
        if (document == null) {
            // Document IDs are database-generated and are never reused.
            deleteSafely(vectorStore, vectors.stream().map(SemanticStore.VectorReference::id).toList());
            return;
        }
        deleteOlderGenerations(vectorStore, vectors, document.getSemanticIndexGeneration());
    }

    private List<Document> toDocuments(long documentId, String version, String sourceHash,
            long documentGeneration, List<SemanticIndexSnapshotReader.SourceChunk> chunks) {
        DocumentMetadata source = documents.findForSemanticRead(documentId)
                .orElseThrow(() -> new IllegalStateException("Document source is unavailable"));
        if (!version.equals(source.getVersion())) throw new IllegalStateException("Document version changed");
        List<Document> result = new ArrayList<>(chunks.size());
        for (SemanticIndexSnapshotReader.SourceChunk chunk : chunks) {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("documentId", documentId);
            metadata.put("documentVersion", version);
            metadata.put("generationKey", properties.generationKey());
            metadata.put("documentGeneration", documentGeneration);
            metadata.put("sourceHash", sourceHash);
            metadata.put("chunkIndex", chunk.index());
            metadata.put("startOffset", chunk.startOffset());
            metadata.put("endOffset", chunk.endOffset());
            metadata.put("documentStatus", DocumentStatus.READY.name());
            metadata.put("outboundApprovalStatus", SemanticReviewStatus.APPROVED.name());
            metadata.put("documentType", source.getDocumentType().name());
            metadata.put("title", source.getTitle());
            put(metadata, "documentKey", source.getDemoDocumentKey());
            if (source.getSupplier() != null) {
                put(metadata, "supplierId", source.getSupplier().getId());
                put(metadata, "supplierCode", source.getSupplier().getCode());
                put(metadata, "supplierName", source.getSupplier().getName());
            }
            put(metadata, "demoDatasetKey", source.getDemoDatasetKey());
            put(metadata, "demoCorpusVersion", source.getDemoCorpusVersion());
            put(metadata, "demoSourceSha256", source.getDemoSourceSha256());
            UUID id = UUID.nameUUIDFromBytes((documentId + "\u001f" + version + "\u001f"
                    + properties.generationKey() + "\u001f" + documentGeneration + "\u001f" + chunk.index())
                    .getBytes(StandardCharsets.UTF_8));
            result.add(Document.builder().id(id.toString()).text(chunk.content()).metadata(metadata).build());
        }
        return List.copyOf(result);
    }

    private boolean isCurrentApproved(long documentId, String version, String sourceHash,
            long documentGeneration, List<SemanticIndexSnapshotReader.SourceChunk> safeChunks) {
        DocumentMetadata document = documents.findForSemanticRead(documentId).orElse(null);
        if (document == null || document.getStatus() != DocumentStatus.READY
                || !version.equals(document.getVersion())
                || document.getSemanticIndexGeneration() != documentGeneration
                || document.getSemanticReviewStatus() != SemanticReviewStatus.APPROVED
                || !sourceHash.equals(document.getSemanticApprovedSourceHash())
                || !version.equals(document.getSemanticReviewVersion())
                || !demoEligibility.isEligible(document)) {
            return false;
        }
        List<DocumentChunk> source = chunks.findByDocumentIdOrderByChunkIndex(documentId);
        if (source.isEmpty() || !sourceHash.equals(SemanticBackfillService.sourceHash(source))) return false;
        OutboundDocumentPolicy.Decision decision = demoEligibility.inspectSource(document, source,
                sourceHash, documentGeneration, outboundPolicy);
        if (!"APPROVED".equals(decision.status()) || decision.safeChunks().size() != safeChunks.size()) return false;
        for (int index = 0; index < safeChunks.size(); index++) {
            DocumentChunk original = source.get(index);
            SemanticIndexSnapshotReader.SourceChunk candidate = safeChunks.get(index);
            if (candidate.index() != original.getChunkIndex()
                    || candidate.startOffset() != original.getStartOffset()
                    || candidate.endOffset() != original.getEndOffset()
                    || !candidate.content().equals(decision.safeChunks().get(index))) return false;
        }
        return true;
    }

    private void deleteSafely(PgVectorStore vectorStore, List<String> ids) {
        try {
            vectorStore.delete(ids);
        } catch (RuntimeException cleanupFailure) {
            logger.warn("Could not remove superseded RAG vectors");
        }
    }

    private void deleteSupersededVectors(PgVectorStore vectorStore, long documentId) {
        try {
            DocumentMetadata document = documents.findForSemanticRead(documentId).orElse(null);
            List<SemanticStore.VectorReference> vectors = registry.vectorReferencesForDocument(documentId);
            if (document == null) {
                // Document IDs are database-generated and are never reused.
                deleteSafely(vectorStore, vectors.stream().map(SemanticStore.VectorReference::id).toList());
                return;
            }
            deleteOlderGenerations(vectorStore, vectors, document.getSemanticIndexGeneration());
        } catch (RuntimeException cleanupFailure) {
            logger.warn("Could not enumerate superseded RAG vectors: documentId={}", documentId);
        }
    }

    private void deleteOlderGenerations(PgVectorStore vectorStore,
            List<SemanticStore.VectorReference> vectors, long generationFence) {
        List<String> staleIds = vectors.stream()
                .filter(vector -> hasGenerationOlderThan(vector, generationFence))
                .map(SemanticStore.VectorReference::id).toList();
        if (!staleIds.isEmpty()) deleteSafely(vectorStore, staleIds);
    }

    private boolean hasGenerationOlderThan(SemanticStore.VectorReference vector, long generationFence) {
        if (vector.documentGeneration() == null) return false;
        try {
            return Long.parseLong(vector.documentGeneration()) < generationFence;
        } catch (NumberFormatException invalidGeneration) {
            return false;
        }
    }

    private void put(Map<String, Object> metadata, String key, Object value) {
        if (value != null) metadata.put(key, value);
    }
}
