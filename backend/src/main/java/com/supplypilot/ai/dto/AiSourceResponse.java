package com.supplypilot.ai.dto;

public record AiSourceResponse(
        String resourceType,
        String resourceId,
        String title,
        String version,
        Long supplierId,
        Integer chunkIndex,
        Integer startOffset,
        Integer endOffset,
        String excerpt,
        Double relevanceScore
) {
    public static AiSourceResponse resource(String resourceType, Object resourceId, String title) {
        return new AiSourceResponse(resourceType, String.valueOf(resourceId), title, null,
                null, null, null, null, null, null);
    }
}
