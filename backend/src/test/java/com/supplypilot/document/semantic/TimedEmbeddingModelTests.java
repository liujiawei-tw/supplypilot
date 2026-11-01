package com.supplypilot.document.semantic;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClientResponseException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TimedEmbeddingModelTests {
    @Test
    void forwardsSuccessfulSpringAiEmbeddingOperationsAndDimensions() {
        EmbeddingResponse response = new EmbeddingResponse(List.of(new Embedding(new float[] {1, 0, 0}, 0)));
        EmbeddingModel delegate = model(response, new float[] {0, 1, 0}, 3, null);
        TimedEmbeddingModel timed = new TimedEmbeddingModel(delegate, Duration.ofSeconds(1));
        try {
            assertThat(timed.call(new EmbeddingRequest(List.of("query"), null))).isSameAs(response);
            assertThat(timed.embed(new Document("chunk"))).containsExactly(0, 1, 0);
            assertThat(timed.dimensions()).isEqualTo(3);
        } finally {
            timed.close();
        }
    }

    @Test
    void cancelsProviderCallAtConfiguredTimeoutAndClassifiesItAsRetryableTimeout() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        EmbeddingModel delegate = model(null, null, 3, () -> {
            started.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return new EmbeddingResponse(List.of(new Embedding(new float[] {1, 0, 0}, 0)));
        });
        TimedEmbeddingModel timed = new TimedEmbeddingModel(delegate, Duration.ofMillis(30));
        try {
            assertThatThrownBy(() -> timed.call(new EmbeddingRequest(List.of("query"), null)))
                    .isInstanceOfSatisfying(SemanticProviderException.class, failure -> {
                        assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.AI_TIMEOUT);
                        assertThat(failure.safeFailureCode()).isEqualTo("PROVIDER_TIMEOUT");
                        assertThat(failure.retryable()).isTrue();
                    });
            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
        } finally {
            timed.close();
        }
    }

    @Test
    void classifiesProviderStatusesAndKeepsRetryAfterForRateLimits() {
        assertFailure(429, "PROVIDER_RATE_LIMITED", true, ErrorCode.AI_PROVIDER_UNAVAILABLE, "7");
        assertFailure(408, "PROVIDER_TIMEOUT", true, ErrorCode.AI_TIMEOUT, null);
        assertFailure(504, "PROVIDER_TIMEOUT", true, ErrorCode.AI_TIMEOUT, null);
        assertFailure(503, "PROVIDER_UNAVAILABLE", true, ErrorCode.AI_PROVIDER_UNAVAILABLE, null);
        assertFailure(400, "PROVIDER_REJECTED", false, ErrorCode.AI_INVALID_RESPONSE, null);
    }

    @Test
    void hidesProviderBodyAndClassifiesUnknownFailureAsRetryableUnavailable() {
        TimedEmbeddingModel timed = new TimedEmbeddingModel(
                model(null, null, 3, () -> { throw new IllegalStateException("private provider body"); }),
                Duration.ofSeconds(1));
        try {
            assertThatThrownBy(() -> timed.embed(new Document("safe chunk")))
                    .isInstanceOfSatisfying(SemanticProviderException.class, failure -> {
                        assertThat(failure.getErrorCode()).isEqualTo(ErrorCode.AI_PROVIDER_UNAVAILABLE);
                        assertThat(failure.safeFailureCode()).isEqualTo("PROVIDER_UNAVAILABLE");
                        assertThat(failure.retryable()).isTrue();
                        assertThat(failure).hasMessageNotContaining("private provider body");
                    });
        } finally {
            timed.close();
        }
    }

    @Test
    void preservesBusinessFailuresFromEmbeddingDelegate() {
        BusinessException unavailable = new BusinessException(ErrorCode.AI_INVALID_RESPONSE);
        TimedEmbeddingModel timed = new TimedEmbeddingModel(
                model(null, null, 3, () -> { throw unavailable; }), Duration.ofSeconds(1));
        try {
            assertThatThrownBy(() -> timed.embed(new Document("safe chunk"))).isSameAs(unavailable);
        } finally {
            timed.close();
        }
    }

    private void assertFailure(int status, String safeCode, boolean retryable,
            ErrorCode errorCode, String retryAfter) {
        HttpHeaders headers = new HttpHeaders();
        if (retryAfter != null) headers.set(HttpHeaders.RETRY_AFTER, retryAfter);
        RestClientResponseException response = new RestClientResponseException(
                "provider response", HttpStatusCode.valueOf(status), "status", headers,
                "private provider body".getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
        TimedEmbeddingModel timed = new TimedEmbeddingModel(
                model(null, null, 3, () -> { throw response; }), Duration.ofSeconds(1));
        try {
            assertThatThrownBy(() -> timed.call(new EmbeddingRequest(List.of("safe query"), null)))
                    .isInstanceOfSatisfying(SemanticProviderException.class, failure -> {
                        assertThat(failure.getErrorCode()).isEqualTo(errorCode);
                        assertThat(failure.safeFailureCode()).isEqualTo(safeCode);
                        assertThat(failure.retryable()).isEqualTo(retryable);
                        assertThat(failure.retryAfter()).isEqualTo(
                                retryAfter == null ? null : Duration.ofSeconds(Long.parseLong(retryAfter)));
                        assertThat(failure).hasMessageNotContaining("private provider body");
                    });
        } finally {
            timed.close();
        }
    }

    private EmbeddingModel model(EmbeddingResponse response, float[] vector, int dimensions,
            ThrowingCall call) {
        return new EmbeddingModel() {
            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                if (call != null) return call.run();
                return response;
            }

            @Override
            public float[] embed(Document document) {
                if (call != null) return call.run().getResults().getFirst().getOutput();
                return vector;
            }

            @Override
            public int dimensions() {
                return dimensions;
            }
        };
    }

    @FunctionalInterface
    private interface ThrowingCall {
        EmbeddingResponse run();
    }
}
