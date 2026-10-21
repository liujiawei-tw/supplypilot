package com.supplypilot.document.mapper;

import com.supplypilot.document.domain.DocumentMetadata;
import com.supplypilot.document.dto.response.DocumentResponse;
import org.springframework.stereotype.Component;

@Component
public class DocumentMapper {

    public DocumentResponse toResponse(DocumentMetadata document) {
        return new DocumentResponse(
                document.getId(),
                document.getDocumentType(),
                document.getSupplier() == null ? null : document.getSupplier().getId(),
                document.getTitle(),
                document.getVersion(),
                document.getStoragePath(),
                document.getStatus(),
                document.getUploadedByUserIdSnapshot(),
                document.getUploadedAt(),
                document.getUpdatedAt()
        );
    }
}
