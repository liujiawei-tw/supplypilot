package com.supplypilot.ai.client;

import java.util.List;
import java.util.Set;

import com.supplypilot.ai.dto.AiToolType;
import com.supplypilot.ai.tool.AiToolResult;

public record AiProviderResponse(List<AiToolResult> results, String answer,
                                 String answerSource, Integer totalTokens, String summary,
                                 Set<AiToolType> requestedTools) {
    public AiProviderResponse {
        requestedTools = requestedTools == null ? Set.of() : Set.copyOf(requestedTools);
    }

    public AiProviderResponse(List<AiToolResult> results, String answer, String answerSource, Integer totalTokens) {
        this(results, answer, answerSource, totalTokens, null, Set.of());
    }

    public AiProviderResponse(List<AiToolResult> results, String answer, String answerSource,
                              Integer totalTokens, String summary) {
        this(results, answer, answerSource, totalTokens, summary, Set.of());
    }
}
