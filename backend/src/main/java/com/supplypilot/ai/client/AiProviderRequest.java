package com.supplypilot.ai.client;

import java.util.List;

import com.supplypilot.ai.dto.AiSourceResponse;
import com.supplypilot.ai.dto.AiToolType;

public record AiProviderRequest(
        String message,
        AiToolType tool,
        String trustedFacts,
        List<AiSourceResponse> sources
) {
}
