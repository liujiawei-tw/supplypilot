package com.supplypilot.ai.tool;

import com.supplypilot.ai.dto.AiToolType;

public record AiQueryPlan(
        AiToolType tool,
        Long recommendationId,
        String materialCode,
        String question
) {
}
