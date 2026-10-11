package com.supplypilot.document.dto.response;

import java.util.List;

public record DocumentQueryResponse(
        DocumentQueryStatus status,
        String answer,
        List<DocumentSourceResponse> sources
) {
    public static DocumentQueryResponse insufficientEvidence() {
        return new DocumentQueryResponse(DocumentQueryStatus.INSUFFICIENT_EVIDENCE, null, List.of());
    }
}
