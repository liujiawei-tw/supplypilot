package com.supplypilot.document.rag;

import java.util.Set;

import com.supplypilot.document.domain.DocumentType;

/** Backend-derived source record corresponding to one verified Spring AI context document. */
public record VerifiedRagEvidence(
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
        double relevanceScore,
        boolean demoBaseline,
        String verifiedTemporalRole,
        Integer temporalRoleStartOffset,
        Integer temporalRoleEndOffset,
        Set<String> verifiedMaterialCodes
) {
    public VerifiedRagEvidence {
        verifiedMaterialCodes = verifiedMaterialCodes == null ? Set.of() : Set.copyOf(verifiedMaterialCodes);
    }

    public VerifiedRagEvidence(Long documentId, String documentKey, DocumentType documentType, String title,
            String version, Long supplierId, String supplierCode, String supplierName, int chunkIndex,
            int startOffset, int endOffset, String excerpt, double relevanceScore, boolean demoBaseline,
            String verifiedTemporalRole, Integer temporalRoleStartOffset, Integer temporalRoleEndOffset) {
        this(documentId, documentKey, documentType, title, version, supplierId, supplierCode, supplierName,
                chunkIndex, startOffset, endOffset, excerpt, relevanceScore, demoBaseline, verifiedTemporalRole,
                temporalRoleStartOffset, temporalRoleEndOffset, Set.of());
    }

    public VerifiedRagEvidence(Long documentId, String documentKey, DocumentType documentType, String title,
            String version, Long supplierId, String supplierCode, String supplierName, int chunkIndex,
            int startOffset, int endOffset, String excerpt, double relevanceScore, boolean demoBaseline) {
        this(documentId, documentKey, documentType, title, version, supplierId, supplierCode, supplierName,
                chunkIndex, startOffset, endOffset, excerpt, relevanceScore, demoBaseline, null, null, null, Set.of());
    }

    public boolean hasVerifiedTemporalRole(String role) {
        return role != null && role.equals(verifiedTemporalRole)
                && temporalRoleStartOffset != null && temporalRoleEndOffset != null
                && temporalRoleStartOffset >= 0 && temporalRoleEndOffset > temporalRoleStartOffset
                && temporalRoleEndOffset <= startOffset;
    }

    public String verifiedTemporalExcerpt() {
        if (verifiedTemporalRole == null) return excerpt;
        String marker = switch (verifiedTemporalRole) {
            case "HISTORICAL" -> "【歷史條件】";
            case "CURRENT" -> "【現行條件】";
            default -> "";
        };
        return marker.isBlank() ? excerpt : marker + " " + excerpt;
    }
}
