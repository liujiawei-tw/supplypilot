package com.supplypilot.document.semantic;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SemanticIndexingPropertiesTests {
    @Test
    void retryDelayUsesCappedExponentialBackoffAndHonorsRetryAfterMinimum() {
        SemanticIndexingProperties properties = new SemanticIndexingProperties(true, 1000,
                Duration.ofMinutes(5), 4, Duration.ofSeconds(2), Duration.ofSeconds(10));

        assertThat(properties.retryDelay(1, null)).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.retryDelay(2, null)).isEqualTo(Duration.ofSeconds(4));
        assertThat(properties.retryDelay(4, null)).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.retryDelay(1, Duration.ofSeconds(6))).isEqualTo(Duration.ofSeconds(6));
        assertThat(properties.retryDelay(1, Duration.ofSeconds(60))).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void retryAfterIsMinimumAndDoesNotRaiseLocalBackoffCap() {
        SemanticIndexingProperties properties = new SemanticIndexingProperties(true, 1000,
                Duration.ofMinutes(5), 4, Duration.ofSeconds(2), Duration.ofSeconds(10));

        assertThat(properties.retryDelay(1, Duration.ofSeconds(1))).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.retryDelay(3, Duration.ofSeconds(6))).isEqualTo(Duration.ofSeconds(8));
        assertThat(properties.retryDelay(1, Duration.ofSeconds(60))).isEqualTo(Duration.ofSeconds(60));
        assertThat(properties.retryDelay(5, null)).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.retryDelay(2, Duration.ofSeconds(-1))).isEqualTo(Duration.ofSeconds(4));
    }

    @Test
    void retryAfterOf120SecondsIsNotCappedBySixtySecondLocalMaximum() {
        SemanticIndexingProperties properties = new SemanticIndexingProperties(true, 1000,
                Duration.ofMinutes(5), 4, Duration.ofSeconds(2), Duration.ofSeconds(60));

        assertThat(properties.retryDelay(1, Duration.ofSeconds(120))).isEqualTo(Duration.ofSeconds(120));
        assertThat(properties.retryDelay(10, null)).isEqualTo(Duration.ofSeconds(60));
    }

    @Test
    void retryScheduleRejectsValuesOutsideMysqlDatetimeRangeWithoutOverflow() {
        Instant now = Instant.parse("2026-10-24T04:00:00Z");

        assertThat(SemanticIndexWorker.retryAt(now, Duration.ofSeconds(120)))
                .contains(now.plusSeconds(120));
        assertThat(SemanticIndexWorker.retryAt(now, Duration.ofSeconds(Long.MAX_VALUE))).isEmpty();
        assertThat(SemanticIndexWorker.retryAt(now, Duration.ofSeconds(-1))).isEmpty();
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
