package com.supplypilot.ai.client;

import java.util.List;

import com.supplypilot.ai.tool.AiToolResult;

public record AiProviderResponse(List<AiToolResult> results, String answer,
                                 String answerSource, Integer totalTokens, String summary) {
    public AiProviderResponse(List<AiToolResult> results, String answer, String answerSource, Integer totalTokens) {
        this(results, answer, answerSource, totalTokens, null);
    }
}
