package com.supplypilot.document.mapper;

import com.supplypilot.document.domain.DocumentMetadata;
import com.supplypilot.document.dto.response.DocumentDemoProvenanceResponse;
import com.supplypilot.document.dto.response.DocumentResponse;
import com.supplypilot.document.dto.response.DocumentSemanticIndexResponse;
import com.supplypilot.document.dto.response.DocumentSemanticReviewResponse;
import org.springframework.stereotype.Component;

@Component
public class DocumentMapper {

    public DocumentResponse toResponse(DocumentMetadata document) {
        return toResponse(document, null);
    }

    public DocumentResponse toResponse(DocumentMetadata document, DocumentSemanticIndexResponse semanticIndex) {
        DocumentDemoProvenanceResponse provenance = document.isDemoBaseline()
                ? new DocumentDemoProvenanceResponse(document.getDemoDatasetKey(), document.getDemoDocumentKey(),
                        document.getDemoThemeKey(), document.getDemoCorpusVersion(),
                        document.getDemoSourceSha256(), document.getDemoProvenanceOrigin())
                : null;
        DocumentSemanticReviewResponse review = document.getSemanticReviewStatus() == null ? null
                : new DocumentSemanticReviewResponse(document.getSemanticReviewStatus(),
                        document.getSemanticApprovedSourceHash(), document.getSemanticReviewVersion(),
                        document.getSemanticReviewReason(), document.getSemanticReviewActorType(),
                        document.getSemanticReviewActor(), document.getSemanticReviewTrigger(),
                        document.getSemanticReviewPolicyDecision(), document.getSemanticApprovedAt(),
                        document.getSemanticReviewInvalidatedAt() == null,
                        document.getSemanticReviewInvalidatedAt(), document.getSemanticReviewInvalidationReason());
        return new DocumentResponse(
                document.getId(),
                document.getDocumentType(),
                document.getSupplier() == null ? null : document.getSupplier().getId(),
                document.getSupplier() == null ? null : document.getSupplier().getCode(),
                document.getSupplier() == null ? null : document.getSupplier().getName(),
                document.getTitle(),
                document.getVersion(),
                document.getStoragePath(),
                document.getStatus(),
                document.getUploadedByUserIdSnapshot(),
                document.getUploadedAt(),
                document.getUpdatedAt(),
                provenance,
                review,
                semanticIndex
        );
    }
}
