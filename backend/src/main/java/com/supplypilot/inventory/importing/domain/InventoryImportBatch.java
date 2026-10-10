package com.supplypilot.inventory.importing.domain;

import java.time.Instant;
import java.util.Objects;

import com.supplypilot.user.domain.User;
import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;

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
@Table(name = "inventory_import_batches")
public class InventoryImportBatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String filename;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "uploaded_by", nullable = false)
    private User uploadedBy;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    @Column(name = "row_count", nullable = false)
    private int rowCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private InventoryImportStatus status;

    @Column(name = "error_summary", length = 1000)
    private String errorSummary;

    protected InventoryImportBatch() {
    }

    public static InventoryImportBatch validated(
            String filename, User uploadedBy, Instant uploadedAt, int rowCount, int errorRows
    ) {
        Objects.requireNonNull(uploadedBy, "Uploaded by must not be null");
        Objects.requireNonNull(uploadedAt, "Uploaded at must not be null");
        InventoryImportBatch batch = new InventoryImportBatch();
        batch.filename = sanitizeFilename(filename);
        batch.uploadedBy = uploadedBy;
        batch.uploadedAt = uploadedAt;
        batch.rowCount = rowCount;
        batch.status = errorRows == 0 ? InventoryImportStatus.VALIDATED : InventoryImportStatus.VALIDATION_FAILED;
        batch.errorSummary = errorRows == 0 ? null : errorRows + " row(s) failed validation.";
        return batch;
    }

    public void confirmImported() {
        if (status == InventoryImportStatus.IMPORTED) {
            throw new BusinessException(ErrorCode.INVENTORY_IMPORT_ALREADY_CONFIRMED);
        }
        if (status != InventoryImportStatus.VALIDATED) {
            throw new BusinessException(ErrorCode.INVENTORY_IMPORT_NOT_VALIDATED);
        }
        status = InventoryImportStatus.IMPORTED;
    }

    private static String sanitizeFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "upload.csv";
        }
        String basename = filename.replace('\\', '/');
        int separator = basename.lastIndexOf('/');
        String safeBasename = basename.substring(separator + 1).trim();
        if (safeBasename.isEmpty()) {
            return "upload.csv";
        }
        return safeBasename.substring(0, Math.min(255, safeBasename.length()));
    }

    public Long getId() { return id; }
    public String getFilename() { return filename; }
    public User getUploadedBy() { return uploadedBy; }
    public Instant getUploadedAt() { return uploadedAt; }
    public int getRowCount() { return rowCount; }
    public InventoryImportStatus getStatus() { return status; }
    public String getErrorSummary() { return errorSummary; }
}
