package com.supplypilot.document.rag;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties(prefix = "supplypilot.rag.chunking")
public record RagChunkingProperties(int chunkSize, int overlapCharacters, int maxChunks,
                                    Duration parserTimeout) {

    public RagChunkingProperties(int chunkSize, int overlapCharacters, int maxChunks) {
        this(chunkSize, overlapCharacters, maxChunks, Duration.ofSeconds(30));
    }

    @ConstructorBinding
    public RagChunkingProperties {
        if (chunkSize < 32 || chunkSize > 16_000
                || overlapCharacters < 0 || overlapCharacters > 4_000
                || overlapCharacters >= chunkSize
                || maxChunks < 1 || maxChunks > 10_000
                || parserTimeout == null || parserTimeout.isNegative() || parserTimeout.isZero()
                || parserTimeout.compareTo(Duration.ofMinutes(5)) > 0) {
            throw new IllegalArgumentException("Invalid RAG chunking configuration");
        }
    }
}
