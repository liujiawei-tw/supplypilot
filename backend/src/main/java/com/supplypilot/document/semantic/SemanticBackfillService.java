package com.supplypilot.document.semantic;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.document.domain.DocumentChunk;
import com.supplypilot.document.domain.DocumentMetadata;
import com.supplypilot.document.domain.DocumentStatus;
import com.supplypilot.document.repository.DocumentChunkRepository;
import com.supplypilot.document.repository.DocumentMetadataRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

@Service
public class SemanticBackfillService {
    private final DocumentMetadataRepository documents;
    private final DocumentChunkRepository chunks;
    private final OutboundDocumentPolicy policy;
    private final SemanticEmbeddingService embeddings;
    private final SemanticStore store;

    public SemanticBackfillService(DocumentMetadataRepository documents, DocumentChunkRepository chunks,
                                   OutboundDocumentPolicy policy, SemanticEmbeddingService embeddings,
                                   SemanticStore store) {
        this.documents = documents;
        this.chunks = chunks;
        this.policy = policy;
        this.embeddings = embeddings;
        this.store = store;
    }

    @PreAuthorize("hasRole('ADMIN')")
    public BackfillBatch backfill(long afterId, int limit) {
        if (afterId < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid cursor or limit");
        List<DocumentMetadata> page = documents.findBackfillPage(DocumentStatus.READY,
                afterId, PageRequest.of(0, limit));
        int ready = 0, failed = 0, skipped = 0, review = 0;
        long lastId = afterId;
        for (DocumentMetadata document : page) {
            lastId = document.getId();
            String status = reindex(document);
            switch (status) {
                case "READY" -> ready++;
                case "FAILED" -> failed++;
                case "SKIPPED" -> skipped++;
                case "REQUIRES_REVIEW" -> review++;
                default -> throw new IllegalStateException("Unexpected indexing status");
            }
        }
        return new BackfillBatch(page.size(), ready, failed, skipped, review, lastId,
                page.size() == limit);
    }

    String reindex(DocumentMetadata document) {
        List<DocumentChunk> source = chunks.findByDocumentIdOrderByChunkIndex(document.getId());
        String hash = sourceHash(source);
        OutboundDocumentPolicy.Decision decision = policy.inspect(document,
                source.stream().map(DocumentChunk::getContent).toList(), hash);
        if (!decision.status().equals("APPROVED")) {
            store.mark(document.getId(), document.getVersion(), decision.status(),
                    decision.reason(), hash, source.size());
            return decision.status();
        }
        SemanticStore.IndexState existing = store.state(document.getId(), document.getVersion());
        if (existing != null && "READY".equals(existing.status()) && hash.equals(existing.sourceHash())
                && existing.expectedCount() == source.size()
                && store.matchesSource(document.getId(), document.getVersion(),
                        sourceProof(source, decision.safeChunks()))) {
            return "READY";
        }
        store.mark(document.getId(), document.getVersion(), "PROCESSING", null, hash, source.size());
        try {
            List<SemanticStore.SafeChunk> prepared = new ArrayList<>();
            for (int i = 0; i < source.size(); i++) {
                DocumentChunk chunk = source.get(i);
                prepared.add(new SemanticStore.SafeChunk(chunk.getChunkIndex(), chunk.getStartOffset(),
                        chunk.getEndOffset(), decision.safeChunks().get(i),
                        embeddings.document(decision.safeChunks().get(i))));
            }
            store.replace(document.getId(), document.getVersion(), hash, prepared);
            return "READY";
        } catch (RuntimeException failure) {
            String reason = failure instanceof BusinessException business
                    ? business.getErrorCode().name() : "INDEXING_FAILURE";
            store.mark(document.getId(), document.getVersion(), "FAILED", reason, hash, source.size());
            return "FAILED";
        }
    }

    public static String sourceHash(List<DocumentChunk> chunks) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (DocumentChunk chunk : chunks) {
                digest.update((chunk.getChunkIndex() + ":" + chunk.getStartOffset() + ":"
                        + chunk.getEndOffset() + ":" + chunk.getContent().length() + ":")
                        .getBytes(StandardCharsets.UTF_8));
                digest.update(chunk.getContent().getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static List<SemanticStore.SourceChunk> sourceProof(List<DocumentChunk> source,
                                                        List<String> safeChunks) {
        List<SemanticStore.SourceChunk> proof = new ArrayList<>(source.size());
        for (int i = 0; i < source.size(); i++) {
            DocumentChunk chunk = source.get(i);
            proof.add(new SemanticStore.SourceChunk(chunk.getChunkIndex(), chunk.getStartOffset(),
                    chunk.getEndOffset(), safeChunks.get(i)));
        }
        return List.copyOf(proof);
    }

    public record BackfillBatch(int processed, int ready, int failed, int skipped,
                                int requiresReview, long lastId, boolean morePossible) { }
}
