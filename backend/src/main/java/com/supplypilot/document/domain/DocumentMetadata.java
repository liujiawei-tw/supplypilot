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

    private static final String DEMO_DATASET_KEY = "SP-901";
    private static final String TRUSTED_DEMO_ACTOR = "TRUSTED_DEMO_CORPUS";

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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by")
    private User uploadedBy;

    @Column(name = "uploaded_by_user_id_snapshot")
    private Long uploadedByUserIdSnapshot;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "demo_dataset_key", length = 40)
    private String demoDatasetKey;

    @Column(name = "demo_document_key", length = 120)
    private String demoDocumentKey;

    @Column(name = "demo_theme_key", length = 80)
    private String demoThemeKey;

    @Column(name = "demo_corpus_version", length = 50)
    private String demoCorpusVersion;

    @Column(name = "demo_source_sha256", length = 64)
    private String demoSourceSha256;

    @Column(name = "demo_provenance_origin", length = 30)
    private String demoProvenanceOrigin;

    @Column(name = "semantic_approved_source_hash", length = 64)
    private String semanticApprovedSourceHash;

    @Column(name = "semantic_approved_by")
    private Long semanticApprovedBy;

    @Column(name = "semantic_approved_at")
    private Instant semanticApprovedAt;

    @Column(name = "semantic_review_version", length = 50)
    private String semanticReviewVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "semantic_review_status", length = 30)
    private SemanticReviewStatus semanticReviewStatus;

    @Column(name = "semantic_review_reason", length = 120)
    private String semanticReviewReason;

    @Column(name = "semantic_review_actor_type", length = 20)
    private String semanticReviewActorType;

    @Column(name = "semantic_review_actor", length = 120)
    private String semanticReviewActor;

    @Column(name = "semantic_review_trigger", length = 120)
    private String semanticReviewTrigger;

    @Column(name = "semantic_review_policy_decision", length = 30)
    private String semanticReviewPolicyDecision;

    @Column(name = "semantic_review_invalidated_at")
    private Instant semanticReviewInvalidatedAt;

    @Column(name = "semantic_review_invalidation_reason", length = 80)
    private String semanticReviewInvalidationReason;

    @Column(name = "semantic_index_generation", nullable = false)
    private long semanticIndexGeneration;

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
        document.uploadedByUserIdSnapshot = uploadedBy.getId();
        document.uploadedAt = Objects.requireNonNull(now, "Timestamp must not be null");
        document.updatedAt = now;
        return document;
    }

    public static DocumentMetadata demoBaselineProcessing(
            DocumentType documentType, Supplier supplier, String title, String version, String storagePath,
            String datasetKey, String documentKey, String themeKey, String corpusVersion,
            String sourceSha256, String provenanceOrigin, Instant now) {
        DocumentMetadata document = new DocumentMetadata();
        document.documentType = Objects.requireNonNull(documentType, "Document type must not be null");
        document.supplier = supplier;
        document.title = requiredText(title);
        document.version = requiredText(version);
        document.storagePath = requiredText(storagePath);
        document.status = DocumentStatus.PROCESSING;
        document.uploadedBy = null;
        document.uploadedByUserIdSnapshot = null;
        document.uploadedAt = Objects.requireNonNull(now, "Timestamp must not be null");
        document.updatedAt = now;
        document.setDemoProvenance(datasetKey, documentKey, themeKey, corpusVersion,
                sourceSha256, provenanceOrigin);
        return document;
    }

    /** Assigns catalog identity only after the caller has verified the persisted legacy file. */
    public void adoptDemoBaseline(
            String datasetKey, String documentKey, String themeKey, String corpusVersion,
            String sourceSha256, String provenanceOrigin, Instant now) {
        if (demoDocumentKey != null || demoDatasetKey != null || demoSourceSha256 != null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        setDemoProvenance(datasetKey, documentKey, themeKey, corpusVersion, sourceSha256, provenanceOrigin);
        updatedAt = Objects.requireNonNull(now);
    }

    private void setDemoProvenance(String datasetKey, String documentKey, String themeKey,
                                   String corpusVersion, String sourceSha256, String provenanceOrigin) {
        if (!DEMO_DATASET_KEY.equals(datasetKey) || documentKey == null || !documentKey.matches("[A-Z0-9_-]{1,120}")
                || themeKey == null || !themeKey.matches("[a-z0-9_]{1,80}")
                || corpusVersion == null || corpusVersion.isBlank() || corpusVersion.length() > 50
                || sourceSha256 == null || !sourceSha256.matches("(?i)[0-9a-f]{64}")
                || !("LEGACY_SP906".equals(provenanceOrigin) || "ADDED_SP907".equals(provenanceOrigin))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        demoDatasetKey = datasetKey;
        demoDocumentKey = documentKey;
        demoThemeKey = themeKey;
        demoCorpusVersion = corpusVersion;
        demoSourceSha256 = sourceSha256.toLowerCase(java.util.Locale.ROOT);
        demoProvenanceOrigin = provenanceOrigin;
    }

    /** Public metadata updates may archive a document, but may not forge ingestion results. */
    public void updateMetadata(String newTitle, DocumentStatus requestedStatus, Instant now) {
        Objects.requireNonNull(now, "Timestamp must not be null");
        boolean modified = false;
        if (newTitle != null) {
            String normalizedTitle = requiredText(newTitle);
            if (!normalizedTitle.equals(title)) {
                title = normalizedTitle;
                invalidateSemanticReview("DOCUMENT_METADATA_CHANGED", now);
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

    public void retryFailedDemoBaselineIngestion(Instant now) {
        if (!isDemoBaseline() || status != DocumentStatus.FAILED) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        status = DocumentStatus.PROCESSING;
        updatedAt = Objects.requireNonNull(now);
    }

    /** Human ADMIN review retains its database user identity. */
    public long reviewSemanticOutbound(String sourceHash, Long actorId, Instant now,
                                       SemanticReviewStatus reviewStatus, String reason) {
        if (actorId == null) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        return recordSemanticReview(sourceHash, actorId, "USER", actorId.toString(),
                "ADMIN_OUTBOUND_REVIEW", now, reviewStatus, reason);
    }

    /** System approval is reserved for the trusted DEMO corpus path; it does not impersonate a user. */
    public long reviewTrustedDemoSemanticOutbound(String sourceHash, Instant now,
                                                   SemanticReviewStatus reviewStatus, String reason) {
        if (!isDemoBaseline()) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        return recordSemanticReview(sourceHash, null, "SYSTEM", TRUSTED_DEMO_ACTOR,
                "TRUSTED_DEMO_CORPUS_AUTO_REVIEW", now, reviewStatus, reason);
    }

    private long recordSemanticReview(String sourceHash, Long actorId, String actorType, String actor,
                                      String trigger, Instant now, SemanticReviewStatus reviewStatus, String reason) {
        if (status != DocumentStatus.READY || sourceHash == null || !sourceHash.matches("(?i)[0-9a-f]{64}")
                || now == null || reviewStatus == null
                || (reviewStatus != SemanticReviewStatus.APPROVED && (reason == null || reason.isBlank()
                || reason.length() > 120))) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        String reviewedVersion = version;
        boolean reviewChanged = semanticReviewStatus != reviewStatus
                || !Objects.equals(semanticApprovedSourceHash, sourceHash)
                || !Objects.equals(semanticReviewVersion, reviewedVersion)
                || !Objects.equals(semanticReviewReason, reason)
                || !Objects.equals(semanticReviewActorType, actorType)
                || !Objects.equals(semanticReviewActor, actor)
                || !Objects.equals(semanticReviewTrigger, trigger)
                || !Objects.equals(semanticReviewPolicyDecision, reviewStatus.name());
        if (reviewChanged) {
            if (semanticIndexGeneration == Long.MAX_VALUE) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            semanticIndexGeneration++;
            semanticApprovedSourceHash = sourceHash.toLowerCase(java.util.Locale.ROOT);
            semanticApprovedBy = actorId;
            semanticApprovedAt = now;
            semanticReviewVersion = reviewedVersion;
            semanticReviewStatus = reviewStatus;
            semanticReviewReason = reason;
            semanticReviewActorType = actorType;
            semanticReviewActor = actor;
            semanticReviewTrigger = trigger;
            semanticReviewPolicyDecision = reviewStatus.name();
            semanticReviewInvalidatedAt = null;
            semanticReviewInvalidationReason = null;
            updatedAt = now;
        }
        return semanticIndexGeneration;
    }

    private void invalidateSemanticReview(String reason, Instant now) {
        if (semanticReviewStatus == null || semanticReviewInvalidatedAt != null) return;
        if (semanticIndexGeneration == Long.MAX_VALUE) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        semanticIndexGeneration++;
        semanticReviewInvalidatedAt = now;
        semanticReviewInvalidationReason = reason;
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

    public boolean isDemoBaseline() {
        return demoDatasetKey != null && demoDocumentKey != null && demoThemeKey != null
                && demoCorpusVersion != null && demoSourceSha256 != null && demoProvenanceOrigin != null;
    }

    public boolean hasDemoProvenance(String datasetKey, String documentKey, String version,
                                     String corpusVersion, String sourceSha256, String themeKey,
                                     String provenanceOrigin) {
        return Objects.equals(demoDatasetKey, datasetKey) && Objects.equals(demoDocumentKey, documentKey)
                && Objects.equals(this.version, version) && Objects.equals(demoCorpusVersion, corpusVersion)
                && Objects.equals(demoSourceSha256, sourceSha256) && Objects.equals(demoThemeKey, themeKey)
                && Objects.equals(demoProvenanceOrigin, provenanceOrigin);
    }

    public Long getId() { return id; }
    public DocumentType getDocumentType() { return documentType; }
    public Supplier getSupplier() { return supplier; }
    public String getTitle() { return title; }
    public String getVersion() { return version; }
    public String getStoragePath() { return storagePath; }
    public DocumentStatus getStatus() { return status; }
    public User getUploadedBy() { return uploadedBy; }
    public Long getUploadedByUserIdSnapshot() { return uploadedByUserIdSnapshot; }
    public Instant getUploadedAt() { return uploadedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getDemoDatasetKey() { return demoDatasetKey; }
    public String getDemoDocumentKey() { return demoDocumentKey; }
    public String getDemoThemeKey() { return demoThemeKey; }
    public String getDemoCorpusVersion() { return demoCorpusVersion; }
    public String getDemoSourceSha256() { return demoSourceSha256; }
    public String getDemoProvenanceOrigin() { return demoProvenanceOrigin; }
    public String getSemanticApprovedSourceHash() { return semanticApprovedSourceHash; }
    public Long getSemanticApprovedBy() { return semanticApprovedBy; }
    public Instant getSemanticApprovedAt() { return semanticApprovedAt; }
    public String getSemanticReviewVersion() { return semanticReviewVersion; }
    public SemanticReviewStatus getSemanticReviewStatus() { return semanticReviewStatus; }
    public String getSemanticReviewReason() { return semanticReviewReason; }
    public String getSemanticReviewActorType() { return semanticReviewActorType; }
    public String getSemanticReviewActor() { return semanticReviewActor; }
    public String getSemanticReviewTrigger() { return semanticReviewTrigger; }
    public String getSemanticReviewPolicyDecision() { return semanticReviewPolicyDecision; }
    public Instant getSemanticReviewInvalidatedAt() { return semanticReviewInvalidatedAt; }
    public String getSemanticReviewInvalidationReason() { return semanticReviewInvalidationReason; }
    public long getSemanticIndexGeneration() { return semanticIndexGeneration; }
}
