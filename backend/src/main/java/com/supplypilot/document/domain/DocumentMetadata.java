package com.supplypilot.document.domain;

import java.time.Instant;
import java.util.Objects;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.supplier.domain.Supplier;
import com.supplypilot.user.domain.User;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "document_metadata")
public class DocumentMetadata {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 40)
    private DocumentType documentType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id")
    private Supplier supplier;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, length = 50)
    private String version;

    @Column(name = "storage_path", nullable = false, length = 255)
    private String storagePath;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DocumentStatus status;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "uploaded_by", nullable = false)
    private User uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "semantic_approved_source_hash", length = 64)
    private String semanticApprovedSourceHash;

    @Column(name = "semantic_approved_by")
    private Long semanticApprovedBy;

    @Column(name = "semantic_approved_at")
    private Instant semanticApprovedAt;

    protected DocumentMetadata() {
    }

    public static DocumentMetadata processing(
            DocumentType documentType,
            Supplier supplier,
            String title,
            String version,
            String storagePath,
            User uploadedBy,
            Instant now
    ) {
        DocumentMetadata document = new DocumentMetadata();
        document.documentType = Objects.requireNonNull(documentType, "Document type must not be null");
        document.supplier = supplier;
        document.title = requiredText(title);
        document.version = requiredText(version);
        document.storagePath = requiredText(storagePath);
        document.status = DocumentStatus.PROCESSING;
        document.uploadedBy = Objects.requireNonNull(uploadedBy, "Uploaded by must not be null");
        document.uploadedAt = Objects.requireNonNull(now, "Timestamp must not be null");
        document.updatedAt = now;
        return document;
    }

    /** Public metadata updates may archive a document, but may not forge ingestion results. */
    public void updateMetadata(String newTitle, DocumentStatus requestedStatus, Instant now) {
        Objects.requireNonNull(now, "Timestamp must not be null");
        boolean modified = false;
        if (newTitle != null) {
            String normalizedTitle = requiredText(newTitle);
            if (!normalizedTitle.equals(title)) {
                title = normalizedTitle;
                modified = true;
            }
        }
        if (requestedStatus != null) {
            if (requestedStatus != DocumentStatus.INACTIVE) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            if (status != DocumentStatus.INACTIVE) {
                status = DocumentStatus.INACTIVE;
                modified = true;
            }
        }
        if (modified) {
            updatedAt = now;
        }
    }

    public void markReady(Instant now) {
        transitionFromProcessing(DocumentStatus.READY, now);
    }

    public void markFailed(Instant now) {
        transitionFromProcessing(DocumentStatus.FAILED, now);
    }

    public void approveSemanticOutbound(String sourceHash, Long actorId, Instant now) {
        if (status != DocumentStatus.READY || sourceHash == null || sourceHash.length() != 64
                || actorId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        semanticApprovedSourceHash = sourceHash;
        semanticApprovedBy = actorId;
        semanticApprovedAt = Objects.requireNonNull(now);
        updatedAt = now;
    }

    private void transitionFromProcessing(DocumentStatus target, Instant now) {
        Objects.requireNonNull(now, "Timestamp must not be null");
        if (status != DocumentStatus.PROCESSING) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        status = target;
        updatedAt = now;
    }

    private static String requiredText(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return value.trim();
    }

    public Long getId() { return id; }
    public DocumentType getDocumentType() { return documentType; }
    public Supplier getSupplier() { return supplier; }
    public String getTitle() { return title; }
    public String getVersion() { return version; }
    public String getStoragePath() { return storagePath; }
    public DocumentStatus getStatus() { return status; }
    public User getUploadedBy() { return uploadedBy; }
    public Instant getUploadedAt() { return uploadedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getSemanticApprovedSourceHash() { return semanticApprovedSourceHash; }
    public Long getSemanticApprovedBy() { return semanticApprovedBy; }
    public Instant getSemanticApprovedAt() { return semanticApprovedAt; }
}
