package com.supplypilot.ai.dto;

import java.util.List;

public record AiQueryResponse(
        AiQueryStatus status,
        String answer,
        List<AiToolType> tools,
        List<AiSourceResponse> sources,
        String answerSource,
        AiAnswerData details,
        String summary
) {
    public AiQueryResponse(AiQueryStatus status, String answer, List<AiToolType> tools,
                           List<AiSourceResponse> sources, String answerSource) {
        this(status, answer, tools, sources, answerSource, AiAnswerData.empty(), null);
    }

    public AiQueryResponse(AiQueryStatus status, String answer, List<AiToolType> tools,
                           List<AiSourceResponse> sources, String answerSource, AiAnswerData details) {
        this(status, answer, tools, sources, answerSource, details, null);
    }

    public AiQueryResponse {
        tools = tools == null ? List.of() : List.copyOf(tools);
        sources = sources == null ? List.of() : List.copyOf(sources);
        details = details == null ? AiAnswerData.empty() : details;
    }
}
