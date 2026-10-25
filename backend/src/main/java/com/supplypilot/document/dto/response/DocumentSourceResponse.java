package com.supplypilot.document.dto.response;

import com.supplypilot.document.domain.DocumentType;

public record DocumentSourceResponse(
        Long documentId,
        String documentKey,
        DocumentType documentType,
        String title,
        String version,
        Long supplierId,
        String supplierCode,
        String supplierName,
        int chunkIndex,
        int startOffset,
        int endOffset,
        String excerpt,
        double relevanceScore
) {
}
