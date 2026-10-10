package com.supplypilot.procurement.dto.response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.supplypilot.procurement.domain.PurchaseOrderStatus;

public record PurchaseOrderResponse(
        Long id,
        String poNumber,
        Long supplierId,
        String supplierCode,
        String supplierName,
        PurchaseOrderStatus status,
        LocalDate expectedDeliveryDate,
        List<PurchaseOrderItemResponse> items,
        Long createdBy,
        String createdByDisplayName,
        Instant createdAt,
        Long approvedBy,
        String approvedByDisplayName,
        Instant approvedAt,
        String rejectReason,
        Long rejectedBy,
        String rejectedByDisplayName,
        Instant rejectedAt,
        Long orderedBy,
        String orderedByDisplayName,
        Instant orderedAt,
        Long receivedBy,
        String receivedByDisplayName,
        Instant receivedAt,
        boolean isOverdue,
        Instant updatedAt
) { }
