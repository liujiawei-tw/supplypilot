package com.supplypilot.agentic.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AgenticSqlQueryRequest(
        @NotBlank @Size(max = 2000) String question
) {
}
