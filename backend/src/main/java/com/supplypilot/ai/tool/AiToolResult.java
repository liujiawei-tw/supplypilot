package com.supplypilot.ai.tool;

import java.util.List;

import com.supplypilot.ai.dto.AiQueryStatus;
import com.supplypilot.ai.dto.AiAnswerData;
import com.supplypilot.ai.dto.AiSourceResponse;
import com.supplypilot.ai.dto.AiToolType;

public record AiToolResult(
        AiToolType tool,
        AiQueryStatus status,
        String trustedFacts,
        List<AiSourceResponse> sources,
        AiAnswerData details
) {
    public AiToolResult(AiToolType tool, AiQueryStatus status, String trustedFacts, List<AiSourceResponse> sources) {
        this(tool, status, trustedFacts, sources, AiAnswerData.empty());
    }

    public AiToolResult {
        sources = sources == null ? List.of() : List.copyOf(sources);
        details = details == null ? AiAnswerData.empty() : details;
    }

    public static AiToolResult insufficientEvidence(AiToolType tool) {
        return new AiToolResult(tool, AiQueryStatus.INSUFFICIENT_EVIDENCE, null, List.of(), AiAnswerData.empty());
    }

    public static AiToolResult notFound(AiToolType tool, String message, AiAnswerData details) {
        return new AiToolResult(tool, AiQueryStatus.NOT_FOUND, message, List.of(), details);
    }

    public static AiToolResult needsClarification(AiToolType tool, String message, AiAnswerData details) {
        return new AiToolResult(tool, AiQueryStatus.NEEDS_CLARIFICATION, message, List.of(), details);
    }
}
