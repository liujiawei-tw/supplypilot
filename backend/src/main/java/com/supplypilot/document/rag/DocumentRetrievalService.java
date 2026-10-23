package com.supplypilot.document.rag;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Pattern IDENTIFIER = Pattern.compile(
            "(?i)(?<![\\p{L}\\p{N}])[A-Z0-9]+(?:[-_/][A-Z0-9]+)+(?![\\p{L}\\p{N}])");
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
        List<RetrievedChunk> result = new ArrayList<>();
        for (List<DocumentChunk> source : byDocument.values()) {
            source.sort(Comparator.comparingInt(DocumentChunk::getChunkIndex));
            DocumentMetadata document = source.getFirst().getDocument();
            List<String> originalChunks = source.stream().map(DocumentChunk::getContent).toList();
            String sourceHash = SemanticBackfillService.sourceHash(source);
            OutboundDocumentPolicy.Decision decision = policy.inspect(document, originalChunks, sourceHash);
            if (!"APPROVED".equals(decision.status()) || decision.safeChunks().size() != source.size()) continue;

            double metadataScore = metadataTokenScore(question, metadata(document));
            for (int index = 0; index < source.size(); index++) {
                DocumentChunk original = source.get(index);
                String safeContent = decision.safeChunks().get(index);
                if (safeContent == null || safeContent.isBlank()) continue;
                double contentScore = cosine(queryEmbedding, EmbeddingCodec.decode(original.getEmbedding()));
                double score = Math.max(contentScore, metadataScore * 0.8);
                if (containsIdentifier(safeContent, identifiers) || containsIdentifier(metadata(document), identifiers)) {
                    score = 1.0;
                }
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

    private String metadata(DocumentMetadata document) {
        var supplier = document.getSupplier();
        return String.join(" ", List.of(
                document.getTitle() == null ? "" : document.getTitle(),
                document.getVersion() == null ? "" : document.getVersion(),
                document.getDocumentType() == null ? "" : document.getDocumentType().name(),
                supplier == null || supplier.getName() == null ? "" : supplier.getName(),
                supplier == null || supplier.getCode() == null ? "" : supplier.getCode()));
    }

    private double metadataTokenScore(String question, String metadata) {
        Set<String> queryTokens = new HashSet<>(LocalLexicalEmbeddingProvider.tokens(question));
        if (queryTokens.isEmpty()) return 0;
        Set<String> metadataTokens = new HashSet<>(LocalLexicalEmbeddingProvider.tokens(metadata));
        long matchingTokens = queryTokens.stream().filter(metadataTokens::contains).count();
        return (double) matchingTokens / queryTokens.size();
    }

    private Set<String> identifiers(String text) {
        Set<String> result = new TreeSet<>();
        Matcher matcher = IDENTIFIER.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC));
        while (matcher.find()) result.add(matcher.group().toLowerCase(Locale.ROOT));
        return result;
    }

    private boolean containsIdentifier(String value, Set<String> identifiers) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return identifiers.stream().anyMatch(normalized::contains);
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
