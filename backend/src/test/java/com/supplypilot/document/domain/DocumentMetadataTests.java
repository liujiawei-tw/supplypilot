package com.supplypilot.document.domain;

import java.time.Instant;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.user.domain.User;
import com.supplypilot.user.domain.UserRole;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentMetadataTests {

    private static final Instant UPLOADED_AT = Instant.parse("2026-10-10T01:00:00Z");

    @Test
    void createsProcessingDocumentAndUsesBackendTimestamps() {
        DocumentMetadata document = document();

        assertThat(document.getStatus()).isEqualTo(DocumentStatus.PROCESSING);
        assertThat(document.getSupplier()).isNull();
        assertThat(document.getUploadedAt()).isEqualTo(UPLOADED_AT);
        assertThat(document.getUpdatedAt()).isEqualTo(UPLOADED_AT);
    }

    @Test
    void publicUpdateCanChangeTitleAndArchiveButCannotForgeIngestionStatus() {
        DocumentMetadata document = document();
        Instant archivedAt = UPLOADED_AT.plusSeconds(60);

        document.updateMetadata(" Archived SOP ", DocumentStatus.INACTIVE, archivedAt);

        assertThat(document.getTitle()).isEqualTo("Archived SOP");
        assertThat(document.getStatus()).isEqualTo(DocumentStatus.INACTIVE);
        assertThat(document.getUpdatedAt()).isEqualTo(archivedAt);
        assertThatThrownBy(() -> document.updateMetadata(null, DocumentStatus.READY, archivedAt.plusSeconds(1)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void semanticReviewGenerationChangesOnlyWhenReviewedSnapshotChanges() {
        DocumentMetadata document = document();
        document.markReady(UPLOADED_AT.plusSeconds(1));
        String firstHash = "a".repeat(64);
        String secondHash = "b".repeat(64);

        assertThat(document.reviewSemanticOutbound(firstHash, 42L, UPLOADED_AT.plusSeconds(2),
                SemanticReviewStatus.APPROVED, null)).isEqualTo(1);
        assertThat(document.reviewSemanticOutbound(firstHash, 42L, UPLOADED_AT.plusSeconds(3),
                SemanticReviewStatus.APPROVED, null)).isEqualTo(1);
        assertThat(document.reviewSemanticOutbound(secondHash, 42L, UPLOADED_AT.plusSeconds(4),
                SemanticReviewStatus.APPROVED, null)).isEqualTo(2);
        assertThat(document.reviewSemanticOutbound(secondHash, 42L, UPLOADED_AT.plusSeconds(5),
                SemanticReviewStatus.SKIPPED, "ADMIN_EXCLUDED")).isEqualTo(3);
    }

    @Test
    void trustedSystemReviewDoesNotImpersonateAdminAndIdenticalRetryPreservesAuditTime() {
        String hash = "c".repeat(64);
        DocumentMetadata document = DocumentMetadata.demoBaselineProcessing(DocumentType.SUPPLIER_PROFILE,
                null, "Supplier profile", "v1", "baseline.txt", "SP-901", "DOC-SUP-001-PROFILE",
                "supplier_profile", "sp-907-zh-tw-v3", hash, "ADDED_SP907", UPLOADED_AT);
        document.markReady(UPLOADED_AT.plusSeconds(1));

        assertThat(document.reviewTrustedDemoSemanticOutbound(hash, UPLOADED_AT.plusSeconds(2),
                SemanticReviewStatus.APPROVED, null)).isEqualTo(1);
        Instant reviewedAt = document.getSemanticApprovedAt();
        Instant updatedAt = document.getUpdatedAt();
        assertThat(document.getSemanticApprovedBy()).isNull();
        assertThat(document.getSemanticReviewActorType()).isEqualTo("SYSTEM");
        assertThat(document.getSemanticReviewActor()).isEqualTo("TRUSTED_DEMO_CORPUS");
        assertThat(document.getSemanticReviewPolicyDecision()).isEqualTo("APPROVED");

        assertThat(document.reviewTrustedDemoSemanticOutbound(hash, UPLOADED_AT.plusSeconds(60),
                SemanticReviewStatus.APPROVED, null)).isEqualTo(1);
        assertThat(document.getSemanticApprovedAt()).isEqualTo(reviewedAt);
        assertThat(document.getUpdatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void metadataChangeInvalidatesAStoredReviewAndAdvancesGeneration() {
        DocumentMetadata document = DocumentMetadata.demoBaselineProcessing(DocumentType.SUPPLIER_PROFILE,
                null, "Supplier profile", "v1", "baseline.txt", "SP-901", "DOC-SUP-001-PROFILE",
                "supplier_profile", "sp-907-zh-tw-v3", "d".repeat(64), "ADDED_SP907", UPLOADED_AT);
        document.markReady(UPLOADED_AT.plusSeconds(1));
        document.reviewTrustedDemoSemanticOutbound("e".repeat(64), UPLOADED_AT.plusSeconds(2),
                SemanticReviewStatus.APPROVED, null);

        document.updateMetadata("Changed profile", null, UPLOADED_AT.plusSeconds(3));

        assertThat(document.getSemanticIndexGeneration()).isEqualTo(2);
        assertThat(document.getSemanticReviewInvalidatedAt()).isEqualTo(UPLOADED_AT.plusSeconds(3));
        assertThat(document.getSemanticReviewInvalidationReason()).isEqualTo("DOCUMENT_METADATA_CHANGED");
    }

    @Test
    void ingestionTransitionsOnlyFromProcessing() {
        DocumentMetadata ready = document();
        ready.markReady(UPLOADED_AT.plusSeconds(1));
        assertThat(ready.getStatus()).isEqualTo(DocumentStatus.READY);
        assertThatThrownBy(() -> ready.markFailed(UPLOADED_AT.plusSeconds(2)))
                .isInstanceOf(BusinessException.class);

        DocumentMetadata failed = document();
        failed.markFailed(UPLOADED_AT.plusSeconds(1));
        assertThat(failed.getStatus()).isEqualTo(DocumentStatus.FAILED);
    }

    private DocumentMetadata document() {
        User user = User.create("document-user", "hash", "Document User", UserRole.ADMIN, UPLOADED_AT);
        return DocumentMetadata.processing(
                DocumentType.PROCUREMENT_SOP, null, "SOP", "v1", "generated.pdf", user, UPLOADED_AT);
    }
}
