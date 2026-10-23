package com.supplypilot.document.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.document.domain.DocumentChunk;
import com.supplypilot.document.domain.DocumentMetadata;
import com.supplypilot.document.domain.DocumentStatus;
import com.supplypilot.document.repository.DocumentChunkRepository;
import com.supplypilot.document.semantic.OutboundDocumentPolicy;
import com.supplypilot.document.semantic.SemanticBackfillService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** MySQL lexical candidate retrieval. Only approved, current, policy-sanitized source chunks leave this service. */
@Service
public class DocumentRetrievalService {

    private static final int LIMIT = 5;
    private final DocumentChunkRepository chunks;
    private final EmbeddingProvider embeddings;
    private final OutboundDocumentPolicy policy;

    public DocumentRetrievalService(DocumentChunkRepository chunks, EmbeddingProvider embeddings,
                                    OutboundDocumentPolicy policy) {
        this.chunks = chunks;
        this.embeddings = embeddings;
        this.policy = policy;
    }

    @Transactional(readOnly = true)
    public List<RetrievedChunk> retrieve(String question) {
        List<Double> queryEmbedding = embed(policy.safeQuery(question));
        Map<Long, List<DocumentChunk>> byDocument = new LinkedHashMap<>();
        for (DocumentChunk chunk : chunks.findRetrievable(DocumentStatus.READY)) {
            DocumentMetadata document = chunk.getDocument();
            if (document != null && document.getId() != null && document.getStatus() == DocumentStatus.READY) {
                byDocument.computeIfAbsent(document.getId(), ignored -> new ArrayList<>()).add(chunk);
            }
        }

        Set<String> identifiers = identifiers(question);
        Set<String> supplierIdentifiers = identifiers.stream()
                .filter(identifier -> identifier.startsWith("SUP-")).collect(java.util.stream.Collectors.toSet());
        Set<String> requestedMaterialIdentifiers = extractMaterialIdentifiers(identifiers);
        List<RetrievedChunk> result = new ArrayList<>();
        for (List<DocumentChunk> source : byDocument.values()) {
            source.sort(Comparator.comparingInt(DocumentChunk::getChunkIndex));
            DocumentMetadata document = source.getFirst().getDocument();
            List<String> originalChunks = source.stream().map(DocumentChunk::getContent).toList();
            String sourceHash = SemanticBackfillService.sourceHash(source);
            OutboundDocumentPolicy.Decision decision = policy.inspect(document, originalChunks, sourceHash);
            if (!"APPROVED".equals(decision.status()) || decision.safeChunks().size() != source.size()) continue;
            if (!isSupplierDocumentCandidate(document, supplierIdentifiers, decision.safeChunks())) continue;

            for (int index = 0; index < source.size(); index++) {
                DocumentChunk original = source.get(index);
                String safeContent = decision.safeChunks().get(index);
                if (safeContent == null || safeContent.isBlank()) continue;
                if (hasConflictingMaterialIdentifier(safeContent, requestedMaterialIdentifiers)) continue;
                double contentScore = cosine(queryEmbedding, EmbeddingCodec.decode(original.getEmbedding()));
                double score = Math.max(contentScore, tokenScore(question, safeContent));
                if (score <= 0) continue;
                DocumentChunk safe = safeContent.equals(original.getContent()) ? original
                        : DocumentChunk.create(document, original.getChunkIndex(), original.getStartOffset(),
                                original.getEndOffset(), safeContent, original.getEmbedding(),
                                original.getCreatedAt());
                result.add(new RetrievedChunk(safe, Math.min(1.0, score)));
            }
        }
        return result.stream()
                .sorted(Comparator.comparingDouble(RetrievedChunk::score).reversed()
                        .thenComparing(candidate -> candidate.chunk().getDocument().getId())
                        .thenComparingInt(candidate -> candidate.chunk().getChunkIndex()))
                .limit(LIMIT)
                .toList();
    }

    private List<Double> embed(String text) {
        try {
            List<Double> vector = embeddings.embed(text);
            EmbeddingCodec.encode(vector);
            return vector;
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
        }
    }

    private boolean isSupplierDocumentCandidate(DocumentMetadata document, Set<String> supplierIdentifiers,
                                                List<String> safeChunks) {
        if (supplierIdentifiers.isEmpty()) return true;
        Set<String> metadataSupplierIdentifiers = document.getSupplier() == null
                || document.getSupplier().getCode() == null
                ? Set.of() : IdentifierMatcher.find(document.getSupplier().getCode()).stream()
                .filter(identifier -> identifier.startsWith("SUP-")).collect(java.util.stream.Collectors.toSet());
        // Metadata admits the document as a candidate; chunk relevance and evidence remain content-only.
        if (!metadataSupplierIdentifiers.isEmpty()) return metadataSupplierIdentifiers.containsAll(supplierIdentifiers);
        return safeChunks.stream().anyMatch(content -> IdentifierMatcher.containsAll(content, supplierIdentifiers));
    }

    private boolean hasConflictingMaterialIdentifier(String content, Set<String> materialIdentifiers) {
        if (materialIdentifiers.isEmpty()) return false;
        Set<String> contentMaterials = extractMaterialIdentifiers(IdentifierMatcher.find(content));
        return !contentMaterials.isEmpty()
                && materialIdentifiers.stream().noneMatch(contentMaterials::contains);
    }

    private double tokenScore(String question, String content) {
        Set<String> queryTokens = new HashSet<>(LocalLexicalEmbeddingProvider.tokens(question));
        if (queryTokens.isEmpty()) return 0;
        Set<String> contentTokens = new HashSet<>(LocalLexicalEmbeddingProvider.tokens(content));
        long matchingTokens = queryTokens.stream().filter(contentTokens::contains).count();
        return (double) matchingTokens / queryTokens.size();
    }

    private Set<String> identifiers(String text) {
        return IdentifierMatcher.find(text);
    }

    private Set<String> extractMaterialIdentifiers(Set<String> identifiers) {
        return identifiers.stream().filter(identifier -> identifier.startsWith("MAT-")
                || identifier.startsWith("MATERIAL-")).collect(java.util.stream.Collectors.toSet());
    }

    private double cosine(List<Double> left, List<Double> right) {
        if (right == null || left.size() != right.size() || left.isEmpty()) {
            throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
        }
        double dot = 0;
        double leftNorm = 0;
        double rightNorm = 0;
        for (int index = 0; index < left.size(); index++) {
            dot += left.get(index) * right.get(index);
            leftNorm += left.get(index) * left.get(index);
            rightNorm += right.get(index) * right.get(index);
        }
        if (leftNorm == 0 || rightNorm == 0) return 0;
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }
}
