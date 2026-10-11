package com.supplypilot.document.rag;

import java.util.Comparator;
import java.util.List;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.document.domain.DocumentStatus;
import com.supplypilot.document.repository.DocumentChunkRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentRetrievalService {

    private static final int LIMIT = 5;
    private final DocumentChunkRepository chunks;
    private final EmbeddingProvider embeddings;

    public DocumentRetrievalService(DocumentChunkRepository chunks, EmbeddingProvider embeddings) {
        this.chunks = chunks;
        this.embeddings = embeddings;
    }

    @Transactional(readOnly = true)
    public List<RetrievedChunk> retrieve(String question) {
        final List<Double> queryEmbedding;
        try {
            queryEmbedding = embeddings.embed(question);
            EmbeddingCodec.encode(queryEmbedding);
        } catch (IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
        }
        return chunks.findRetrievable(DocumentStatus.READY).stream()
                .map(chunk -> new RetrievedChunk(chunk, cosine(queryEmbedding, EmbeddingCodec.decode(chunk.getEmbedding()))))
                .filter(result -> result.score() > 0)
                .sorted(Comparator.comparingDouble(RetrievedChunk::score).reversed())
                .limit(LIMIT)
                .toList();
    }

    private double cosine(List<Double> left, List<Double> right) {
        if (left.size() != right.size() || left.isEmpty()) {
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
        if (leftNorm == 0 || rightNorm == 0) {
            return 0;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }
}
