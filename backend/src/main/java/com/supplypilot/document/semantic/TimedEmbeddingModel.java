package com.supplypilot.document.semantic;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.annotation.PreDestroy;
import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClientResponseException;

/** Applies the configured overall timeout and safe retry classification to Spring AI embedding calls. */
final class TimedEmbeddingModel implements EmbeddingModel {
    private final EmbeddingModel delegate;
    private final Duration timeout;
    private final ExecutorService executor = Executors.newFixedThreadPool(4, Thread.ofVirtual().factory());

    TimedEmbeddingModel(EmbeddingModel delegate, Duration timeout) {
        this.delegate = delegate;
        this.timeout = timeout;
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        return execute(() -> delegate.call(request));
    }

    @Override
    public float[] embed(Document document) {
        return execute(() -> delegate.embed(document));
    }

    @Override
    public int dimensions() {
        return delegate.dimensions();
    }

    @PreDestroy
    void close() {
        executor.shutdownNow();
    }

    private <T> T execute(java.util.concurrent.Callable<T> operation) {
        Future<T> pending = executor.submit(operation);
        try {
            return pending.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException failure) {
            pending.cancel(true);
            throw new SemanticProviderException(ErrorCode.AI_TIMEOUT, true, null, "PROVIDER_TIMEOUT");
        } catch (InterruptedException failure) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new SemanticProviderException(ErrorCode.AI_TIMEOUT, true, null, "PROVIDER_TIMEOUT");
        } catch (ExecutionException failure) {
            throw classify(failure.getCause() == null ? failure : failure.getCause());
        }
    }

    private RuntimeException classify(Throwable failure) {
        if (failure instanceof SemanticProviderException provider) return provider;
        if (failure instanceof BusinessException business) return business;
        RestClientResponseException response = findResponseException(failure);
        if (response == null) {
            return new SemanticProviderException(ErrorCode.AI_PROVIDER_UNAVAILABLE, true, null,
                    "PROVIDER_UNAVAILABLE");
        }
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

    private RestClientResponseException findResponseException(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof RestClientResponseException response) return response;
        }
        return null;
    }

    private Duration retryAfter(String header) {
        if (header == null || header.isBlank()) return null;
        String value = header.trim();
        if (value.matches("[0-9]+")) {
            try {
                return Duration.ofSeconds(Long.parseLong(value));
            } catch (ArithmeticException | NumberFormatException ignored) {
                return null;
            }
        }
        try {
            Duration delay = Duration.between(Instant.now(),
                    ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
            return delay.isNegative() ? Duration.ZERO : delay;
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
