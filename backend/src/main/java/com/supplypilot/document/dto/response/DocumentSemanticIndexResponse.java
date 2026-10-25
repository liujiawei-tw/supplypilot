package com.supplypilot.document.dto.response;

public record DocumentSemanticIndexResponse(
        String status,
        Long jobId,
        String sourceHash,
        int expectedChunks,
        int publishedChunks,
        String failureCode,
        String generationKey,
        Long publicationGeneration
) { }
