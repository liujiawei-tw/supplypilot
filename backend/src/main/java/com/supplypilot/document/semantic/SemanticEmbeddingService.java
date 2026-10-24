package com.supplypilot.document.semantic;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
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
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

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
            throw new SemanticProviderException(ErrorCode.AI_PROVIDER_UNAVAILABLE, true, null,
                    "PROVIDER_UNAVAILABLE");
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
            throw new SemanticProviderException(ErrorCode.AI_TIMEOUT, true, null, "PROVIDER_TIMEOUT");
        } catch (InterruptedException exception) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new SemanticProviderException(ErrorCode.AI_TIMEOUT, true, null, "PROVIDER_TIMEOUT");
        } catch (Exception exception) {
            Throwable cause = exception instanceof ExecutionException && exception.getCause() != null
                    ? exception.getCause() : exception;
            throw classifyProviderFailure(cause);
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

    private SemanticProviderException classifyProviderFailure(Throwable cause) {
        RestClientResponseException response = findResponseException(cause);
        if (response != null) {
            int status = response.getStatusCode().value();
            if (status == 429) {
                return new SemanticProviderException(ErrorCode.AI_PROVIDER_UNAVAILABLE, true,
                        retryAfter(response.getResponseHeaders() == null ? null
                                : response.getResponseHeaders().getFirst(HttpHeaders.RETRY_AFTER)),
                        "PROVIDER_RATE_LIMITED");
            }
            if (status == 408 || status == 504) {
                return new SemanticProviderException(ErrorCode.AI_TIMEOUT, true, null, "PROVIDER_TIMEOUT");
            }
            if (status >= 500) {
                return new SemanticProviderException(ErrorCode.AI_PROVIDER_UNAVAILABLE, true, null,
                        "PROVIDER_UNAVAILABLE");
            }
            return new SemanticProviderException(ErrorCode.AI_INVALID_RESPONSE, false, null,
                    "PROVIDER_REJECTED");
        }
        return new SemanticProviderException(ErrorCode.AI_PROVIDER_UNAVAILABLE, true, null,
                "PROVIDER_UNAVAILABLE");
    }

    private static RestClientResponseException findResponseException(Throwable cause) {
        for (Throwable current = cause; current != null; current = current.getCause()) {
            if (current instanceof RestClientResponseException response) return response;
        }
        return null;
    }

    static Duration retryAfter(String header) {
        return retryAfter(header, Instant.now());
    }

    static Duration retryAfter(String header, Instant now) {
        if (header == null || header.isBlank()) return null;
        String value = header.trim();
        if (value.matches("[0-9]+")) {
            try {
                return Duration.ofSeconds(Long.parseLong(value));
            } catch (ArithmeticException | NumberFormatException invalidSeconds) {
                return null;
            }
        }
        try {
            Instant retryAt = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Duration delay = Duration.between(now, retryAt);
            return delay.isNegative() ? Duration.ZERO : delay;
        } catch (RuntimeException invalidDate) {
            return null;
        }
    }
}
