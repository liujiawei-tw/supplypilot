package com.supplypilot.document.dto.response;

import java.time.Instant;

import com.supplypilot.document.domain.DocumentStatus;
import com.supplypilot.document.domain.DocumentType;

public record DocumentResponse(
        Long id,
        DocumentType documentType,
        Long supplierId,
        String title,
        String version,
        String storagePath,
        DocumentStatus status,
        Long uploadedBy,
        Instant uploadedAt,
        Instant updatedAt
) {
}
