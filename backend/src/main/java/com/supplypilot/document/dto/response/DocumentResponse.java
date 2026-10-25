package com.supplypilot.document.dto.response;

import java.time.Instant;

import com.supplypilot.document.domain.DocumentStatus;
import com.supplypilot.document.domain.DocumentType;

public record DocumentResponse(
        Long id,
        DocumentType documentType,
        Long supplierId,
        String supplierCode,
        String supplierName,
        String title,
        String version,
        String storagePath,
        DocumentStatus status,
        Long uploadedBy,
        Instant uploadedAt,
        Instant updatedAt,
        DocumentDemoProvenanceResponse demoProvenance,
        DocumentSemanticReviewResponse semanticReview,
        DocumentSemanticIndexResponse semanticIndex
) {
}
