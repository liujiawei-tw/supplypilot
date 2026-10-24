package com.supplypilot.document.semantic;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "supplypilot.semantic.indexing")
public record SemanticIndexingProperties(
        boolean workerEnabled,
        int pollIntervalMillis,
        Duration leaseDuration,
        int maxAttempts,
        Duration initialRetryDelay,
        Duration maxRetryDelay
) {
    public SemanticIndexingProperties {
        if (pollIntervalMillis < 100 || leaseDuration == null || leaseDuration.isNegative()
                || leaseDuration.isZero() || maxAttempts < 1 || maxAttempts > 10
                || initialRetryDelay == null || initialRetryDelay.isNegative()
                || maxRetryDelay == null || maxRetryDelay.isNegative()
                || initialRetryDelay.compareTo(maxRetryDelay) > 0) {
            throw new IllegalArgumentException("Invalid semantic indexing configuration");
        }
    }

    public Duration retryDelay(int attempt, Duration retryAfter) {
        if (attempt < 1) throw new IllegalArgumentException("Attempt must be positive");
        if (retryAfter != null && !retryAfter.isNegative()) {
            return retryAfter.compareTo(maxRetryDelay) > 0 ? maxRetryDelay : retryAfter;
        }
        long multiplier = 1L << Math.min(attempt - 1, 30);
        Duration exponential;
        try {
            exponential = initialRetryDelay.multipliedBy(multiplier);
        } catch (ArithmeticException exception) {
            exponential = maxRetryDelay;
        }
        return exponential.compareTo(maxRetryDelay) > 0 ? maxRetryDelay : exponential;
    }
}
