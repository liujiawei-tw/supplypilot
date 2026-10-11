package com.supplypilot.ai.client;

import com.supplypilot.ai.tool.AiToolResult;

public record AiProviderResponse(AiToolResult result, Integer totalTokens) {
}
