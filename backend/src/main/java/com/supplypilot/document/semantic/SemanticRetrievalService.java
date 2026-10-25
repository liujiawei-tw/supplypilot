package com.supplypilot.document.semantic;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.demo.DemoCorpusRagEligibility;
import com.supplypilot.document.domain.DocumentChunk;
import com.supplypilot.document.domain.DocumentMetadata;
import com.supplypilot.document.domain.DocumentStatus;
import com.supplypilot.document.rag.RetrievedChunk;
import com.supplypilot.document.repository.DocumentChunkRepository;
import com.supplypilot.document.repository.DocumentMetadataRepository;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class SemanticRetrievalService {
    private final SemanticStore store;
    private final SemanticEmbeddingService embeddings;
    private final OutboundDocumentPolicy policy;
    private final SemanticProperties properties;
    private final DocumentMetadataRepository documents;
    private final DocumentChunkRepository chunks;
    private final DemoCorpusRagEligibility demoEligibility;
    private final Timer searchTimer;
    private final DistributionSummary candidatesMeter;

    public SemanticRetrievalService(SemanticStore store, SemanticEmbeddingService embeddings,
                                    OutboundDocumentPolicy policy, SemanticProperties properties,
                                    DocumentMetadataRepository documents, DocumentChunkRepository chunks,
                                    MeterRegistry meters) {
        this(store, embeddings, policy, properties, documents, chunks, meters,
                DemoCorpusRagEligibility.manualUploadsOnly());
    }

    @Autowired
    public SemanticRetrievalService(SemanticStore store, SemanticEmbeddingService embeddings,
                                    OutboundDocumentPolicy policy, SemanticProperties properties,
                                    DocumentMetadataRepository documents, DocumentChunkRepository chunks,
                                    MeterRegistry meters, DemoCorpusRagEligibility demoEligibility) {
        this.store = store;
        this.embeddings = embeddings;
        this.policy = policy;
        this.properties = properties;
        this.documents = documents;
        this.chunks = chunks;
        this.demoEligibility = demoEligibility;
        this.searchTimer = Timer.builder("supplypilot.semantic.search.latency")
                .description("Exact pgvector search and MySQL source validation latency")
                .register(meters);
        this.candidatesMeter = DistributionSummary.builder("supplypilot.semantic.search.candidates")
                .description("Exact pgvector candidates checked against current approved MySQL source")
                .register(meters);
    }

    public List<RetrievedChunk> retrieve(String question) {
        long started = System.nanoTime();
        try {
            String safeQuestion = policy.safeQuery(question);
            store.ensureGeneration();
            List<Float> query = embeddings.query(safeQuestion);
            List<RetrievedChunk> result = new ArrayList<>();
            int batchSize = properties.topK() * 5;
            int offset = 0;
            boolean belowThreshold = false;
            while (result.size() < properties.topK() && !belowThreshold) {
                List<SemanticStore.Candidate> candidates = store.search(query, batchSize, offset);
                candidatesMeter.record(candidates.size());
                offset += candidates.size();
                for (SemanticStore.Candidate candidate : candidates) {
                    if (candidate.score() < properties.similarityThreshold()) {
                        belowThreshold = true;
                        break;
                    }
                    DocumentMetadata document = documents.findForSemanticRead(candidate.documentId()).orElse(null);
                    if (document == null || document.getStatus() != DocumentStatus.READY
                            || !document.getVersion().equals(candidate.version())
                            || !demoEligibility.isEligible(document)) continue;
                    List<DocumentChunk> source = chunks.findByDocumentIdOrderByChunkIndex(candidate.documentId());
                    SemanticStore.IndexState index = store.state(candidate.documentId(), candidate.version());
                    if (index == null || !"READY".equals(index.status())
                            || index.publicationGeneration() != document.getSemanticIndexGeneration()
                            || index.expectedCount() != source.size()
                            || store.chunkCount(candidate.documentId(), candidate.version()) != source.size()
                            || !SemanticBackfillService.sourceHash(source).equals(index.sourceHash())) continue;
                    var decision = demoEligibility.inspectSource(document, source, index.sourceHash(),
                            document.getSemanticIndexGeneration(), policy);
                    if (!"APPROVED".equals(decision.status())) continue;
                    int position = -1;
                    for (int i = 0; i < source.size(); i++) {
                        if (source.get(i).getChunkIndex() == candidate.chunkIndex()) position = i;
                    }
                    if (position < 0 || source.get(position).getStartOffset() != candidate.startOffset()
                            || source.get(position).getEndOffset() != candidate.endOffset()
                            || !decision.safeChunks().get(position).equals(candidate.content())) continue;
                    DocumentChunk safe = DocumentChunk.create(document, candidate.chunkIndex(),
                            candidate.startOffset(), candidate.endOffset(), candidate.content(), "", Instant.now());
                    result.add(new RetrievedChunk(safe, candidate.score()));
                    if (result.size() == properties.topK()) break;
                }
                if (candidates.size() < batchSize) break;
            }
            return List.copyOf(result);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
        } finally {
            searchTimer.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }
}
