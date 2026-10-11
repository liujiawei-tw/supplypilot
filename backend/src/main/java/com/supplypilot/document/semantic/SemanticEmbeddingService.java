package com.supplypilot.document.semantic;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.annotation.PreDestroy;
import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions.TaskType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class SemanticEmbeddingService {
    private final ObjectProvider<EmbeddingModel> model;
    private final SemanticProperties properties;
    private final ExecutorService executor = Executors.newFixedThreadPool(4, Thread.ofVirtual().factory());

    public SemanticEmbeddingService(ObjectProvider<EmbeddingModel> model, SemanticProperties properties) {
        this.model = model;
        this.properties = properties;
    }

    public List<Float> document(String safeContent) {
        return embed(safeContent, TaskType.RETRIEVAL_DOCUMENT);
    }

    public List<Float> query(String safeQuestion) {
        return embed(safeQuestion, TaskType.RETRIEVAL_QUERY);
    }

    @PreDestroy
    void close() {
        executor.shutdownNow();
    }

    private List<Float> embed(String content, TaskType taskType) {
        EmbeddingModel provider = model.getIfAvailable();
        if (provider == null) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
        }
        GoogleGenAiTextEmbeddingOptions options = GoogleGenAiTextEmbeddingOptions.builder()
                .model(properties.modelId()).dimensions(properties.dimensions())
                .taskType(taskType).autoTruncate(false).build();
        Future<float[]> pending = executor.submit(() -> provider.call(
                new EmbeddingRequest(List.of(content), options)).getResult().getOutput());
        final float[] output;
        try {
            output = pending.get(properties.embeddingTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            pending.cancel(true);
            throw new BusinessException(ErrorCode.AI_TIMEOUT);
        } catch (InterruptedException exception) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.AI_TIMEOUT);
        } catch (Exception exception) {
            throw new BusinessException(ErrorCode.AI_PROVIDER_UNAVAILABLE);
        }
        if (output == null || output.length != properties.dimensions()) {
            throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
        }
        double sumSquares = 0;
        for (float value : output) {
            if (!Float.isFinite(value)) throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
            sumSquares += (double) value * value;
        }
        double norm = Math.sqrt(sumSquares);
        if (!Double.isFinite(norm) || norm == 0) {
            throw new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
        }
        List<Float> vector = new ArrayList<>(output.length);
        for (float value : output) {
            vector.add((float) (value / norm));
        }
        return List.copyOf(vector);
    }
}
