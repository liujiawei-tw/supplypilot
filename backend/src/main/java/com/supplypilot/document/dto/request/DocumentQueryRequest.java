package com.supplypilot.document.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DocumentQueryRequest(
        @NotBlank @Size(max = 2000) String question
) {
}
