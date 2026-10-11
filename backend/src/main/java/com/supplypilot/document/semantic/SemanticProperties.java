package com.supplypilot.document.semantic;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "supplypilot.semantic")
public record SemanticProperties(
        String jdbcUrl, String username, String password, String modelId, int dimensions,
        String generationKey, boolean readEnabled, int topK, double similarityThreshold,
        Duration embeddingTimeout
) {
    public static final String CHUNKING_VERSION = "sp710-v1";

    public SemanticProperties {
        if (modelId == null || modelId.isBlank() || generationKey == null || generationKey.isBlank()
                || dimensions < 1 || dimensions > 3072 || topK < 1 || topK > 50
                || similarityThreshold < 0 || similarityThreshold > 1
                || embeddingTimeout == null || embeddingTimeout.isNegative() || embeddingTimeout.isZero()) {
            throw new IllegalArgumentException("Invalid semantic configuration");
        }
    }
}
