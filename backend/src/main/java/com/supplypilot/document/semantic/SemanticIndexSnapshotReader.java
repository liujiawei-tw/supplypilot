package com.supplypilot.document.semantic;

import java.time.Instant;
import java.util.List;

import com.supplypilot.document.domain.DocumentChunk;
import com.supplypilot.document.domain.DocumentMetadata;
import com.supplypilot.document.domain.DocumentStatus;
import com.supplypilot.document.domain.SemanticReviewStatus;
import com.supplypilot.document.repository.DocumentChunkRepository;
import com.supplypilot.document.repository.DocumentMetadataRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Loads and validates an approved source inside a short MySQL read transaction. */
@Service
public class SemanticIndexSnapshotReader {
    private final DocumentMetadataRepository documents;
    private final DocumentChunkRepository chunks;
    private final OutboundDocumentPolicy policy;
    private final SemanticProperties properties;

    public SemanticIndexSnapshotReader(DocumentMetadataRepository documents, DocumentChunkRepository chunks,
                                       OutboundDocumentPolicy policy, SemanticProperties properties) {
        this.documents = documents;
        this.chunks = chunks;
        this.policy = policy;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public Result load(SemanticIndexJob job) {
        DocumentMetadata document = documents.findForSemanticRead(job.documentId()).orElse(null);
        if (document == null) return Result.rejected("DOCUMENT_MISSING");
        if (document.getStatus() != DocumentStatus.READY) return Result.rejected("DOCUMENT_NOT_READY");
        if (!document.getVersion().equals(job.version())) return Result.rejected("VERSION_CHANGED");
        if (document.getSemanticIndexGeneration() != job.documentGeneration()) {
            return Result.rejected("GENERATION_CHANGED");
        }
        if (!properties.generationKey().equals(job.generationKey())) {
            return Result.rejected("MODEL_GENERATION_CHANGED");
        }
        if (document.getSemanticReviewStatus() != SemanticReviewStatus.APPROVED
                || !job.sourceHash().equals(document.getSemanticApprovedSourceHash())
                || !job.version().equals(document.getSemanticReviewVersion())) {
            return Result.rejected("OUTBOUND_APPROVAL_REVOKED");
        }
        List<DocumentChunk> source = chunks.findByDocumentIdOrderByChunkIndex(job.documentId());
        if (source.isEmpty()) return Result.rejected("SOURCE_EMPTY");
        String hash = SemanticBackfillService.sourceHash(source);
        if (!job.sourceHash().equals(hash)) return Result.rejected("SOURCE_CHANGED");
        var decision = policy.inspect(document, source.stream().map(DocumentChunk::getContent).toList(), hash);
        if (!"APPROVED".equals(decision.status())) return Result.rejected("OUTBOUND_POLICY_BLOCKED");
        if (decision.safeChunks().size() != source.size()) return Result.rejected("SOURCE_CHANGED");

        List<SourceChunk> safeChunks = java.util.stream.IntStream.range(0, source.size())
                .mapToObj(index -> {
                    DocumentChunk chunk = source.get(index);
                    return new SourceChunk(chunk.getChunkIndex(), chunk.getStartOffset(), chunk.getEndOffset(),
                            decision.safeChunks().get(index));
                }).toList();
        return Result.accepted(new Snapshot(document.getVersion(), hash, job.documentGeneration(), safeChunks));
    }

    @Transactional(readOnly = true)
    public boolean isCurrentApproved(SemanticIndexJob job) {
        return load(job).snapshot() != null;
    }

    public record SourceChunk(int index, int startOffset, int endOffset, String content) { }
    public record Snapshot(String version, String sourceHash, long documentGeneration,
                           List<SourceChunk> chunks) { }
    public record Result(Snapshot snapshot, String rejectionCode) {
        static Result accepted(Snapshot snapshot) { return new Result(snapshot, null); }
        static Result rejected(String code) { return new Result(null, code); }
    }
}
