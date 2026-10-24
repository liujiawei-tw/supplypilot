package com.supplypilot.document.semantic;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SemanticIndexingPropertiesTests {
    @Test
    void retryDelayUsesCappedExponentialBackoffAndHonorsCappedRetryAfter() {
        SemanticIndexingProperties properties = new SemanticIndexingProperties(true, 1000,
                Duration.ofMinutes(5), 4, Duration.ofSeconds(2), Duration.ofSeconds(10));

        assertThat(properties.retryDelay(1, null)).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.retryDelay(2, null)).isEqualTo(Duration.ofSeconds(4));
        assertThat(properties.retryDelay(4, null)).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.retryDelay(1, Duration.ofSeconds(6))).isEqualTo(Duration.ofSeconds(6));
        assertThat(properties.retryDelay(1, Duration.ofSeconds(60))).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void retryDelayRejectsInvalidAttemptsAndConfiguration() {
        SemanticIndexingProperties properties = new SemanticIndexingProperties(false, 1000,
                Duration.ofMinutes(5), 4, Duration.ofSeconds(2), Duration.ofSeconds(10));

        assertThatThrownBy(() -> properties.retryDelay(0, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SemanticIndexingProperties(true, 1000, Duration.ofMinutes(5),
                4, Duration.ofSeconds(11), Duration.ofSeconds(10))).isInstanceOf(IllegalArgumentException.class);
    }
}
