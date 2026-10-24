package com.supplypilot.document.semantic;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClientResponseException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SemanticEmbeddingServiceTests {
    @Test
    void timeoutCancelsProviderCallAndReturnsAiTimeout() {
        SemanticEmbeddingService service = service(200, new float[] {1, 0, 0}, Duration.ofMillis(10));
        try {
            assertThatThrownBy(() -> service.query("safe question"))
                    .isInstanceOfSatisfying(BusinessException.class,
                            error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.AI_TIMEOUT));
        } finally {
            service.close();
        }
    }

    @Test
    void wrongDimensionAndZeroVectorAreRejected() {
        for (float[] output : List.of(new float[] {1, 0}, new float[] {0, 0, 0})) {
            SemanticEmbeddingService service = service(0, output, Duration.ofSeconds(1));
            try {
                assertThatThrownBy(() -> service.document("safe chunk"))
                        .isInstanceOfSatisfying(BusinessException.class,
                                error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.AI_INVALID_RESPONSE));
            } finally {
                service.close();
            }
        }
    }

    @Test
    void classifiesProviderRateLimitTimeoutServerAndClientFailures() {
        assertProviderFailure(429, "PROVIDER_RATE_LIMITED", true, "7");
        assertProviderFailure(408, "PROVIDER_TIMEOUT", true, null);
        assertProviderFailure(504, "PROVIDER_TIMEOUT", true, null);
        assertProviderFailure(503, "PROVIDER_UNAVAILABLE", true, null);
        assertProviderFailure(400, "PROVIDER_REJECTED", false, null);
    }

    @Test
    void parsesNumericRetryAfterAndIgnoresInvalidValues() {
        assertThat(SemanticEmbeddingService.retryAfter("3")).isEqualTo(Duration.ofSeconds(3));
        assertThat(SemanticEmbeddingService.retryAfter("-1")).isNull();
        assertThat(SemanticEmbeddingService.retryAfter("not-a-date")).isNull();
    }

    private void assertProviderFailure(int status, String safeCode, boolean retryable, String retryAfter) {
        HttpHeaders headers = new HttpHeaders();
        if (retryAfter != null) headers.set("Retry-After", retryAfter);
        RestClientResponseException response = new RestClientResponseException(
                "provider response contains private body", HttpStatusCode.valueOf(status),
                "provider status", headers, "private provider body".getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
        SemanticEmbeddingService service = failureService(response);
        try {
            assertThatThrownBy(() -> service.document("safe chunk"))
                    .isInstanceOfSatisfying(SemanticProviderException.class, error -> {
                        assertThat(error.safeFailureCode()).isEqualTo(safeCode);
                        assertThat(error.retryable()).isEqualTo(retryable);
                        assertThat(error.retryAfter()).isEqualTo(
                                retryAfter == null ? null : Duration.ofSeconds(Long.parseLong(retryAfter)));
                        assertThat(error).hasMessageNotContaining("private provider body");
                    });
        } finally {
            service.close();
        }
    }

    private SemanticEmbeddingService failureService(RuntimeException failure) {
        EmbeddingModel model = new EmbeddingModel() {
            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                throw failure;
            }

            @Override
            public float[] embed(Document document) {
                throw failure;
            }
        };
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("embeddingModel", model);
        SemanticProperties properties = new SemanticProperties("", "", "", "fake-model", 3,
                "fake-model-3", false, 5, 0.45, Duration.ofSeconds(1));
        return new SemanticEmbeddingService(beans.getBeanProvider(EmbeddingModel.class), properties);
    }

    private SemanticEmbeddingService service(long pauseMillis, float[] output, Duration timeout) {
        EmbeddingModel model = new EmbeddingModel() {
            @Override
            public EmbeddingResponse call(EmbeddingRequest request) {
                try {
                    Thread.sleep(pauseMillis);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                return new EmbeddingResponse(List.of(new Embedding(output, 0)));
            }

            @Override
            public float[] embed(Document document) {
                return output;
            }
        };
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        beans.addBean("embeddingModel", model);
        SemanticProperties properties = new SemanticProperties("", "", "", "fake-model", 3,
                "fake-model-3", false, 5, 0.45, timeout);
        return new SemanticEmbeddingService(beans.getBeanProvider(EmbeddingModel.class), properties);
    }
}
