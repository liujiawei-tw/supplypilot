package com.supplypilot.document.semantic;

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
