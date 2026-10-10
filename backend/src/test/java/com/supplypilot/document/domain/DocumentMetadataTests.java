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
