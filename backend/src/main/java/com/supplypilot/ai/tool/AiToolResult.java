package com.supplypilot.ai.tool;

import java.util.List;

import com.supplypilot.ai.dto.AiQueryStatus;
import com.supplypilot.ai.dto.AiSourceResponse;
import com.supplypilot.ai.dto.AiToolType;

public record AiToolResult(
        AiToolType tool,
        AiQueryStatus status,
        String trustedFacts,
        List<AiSourceResponse> sources
) {
    public static AiToolResult insufficientEvidence(AiToolType tool) {
        return new AiToolResult(tool, AiQueryStatus.INSUFFICIENT_EVIDENCE, null, List.of());
    }
}
