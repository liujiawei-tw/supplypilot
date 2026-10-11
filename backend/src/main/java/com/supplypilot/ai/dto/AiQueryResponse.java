package com.supplypilot.ai.dto;

import java.util.List;

public record AiQueryResponse(
        AiQueryStatus status,
        String answer,
        List<AiToolType> tools,
        List<AiSourceResponse> sources,
        String answerSource
) {
}
