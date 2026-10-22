package com.supplypilot.procurement.dto.response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.supplypilot.procurement.domain.PurchaseOrderStatus;
import com.supplypilot.procurement.domain.OrderedProgress;
import com.fasterxml.jackson.annotation.JsonInclude;

public record PurchaseOrderResponse(
        Long id,
        String poNumber,
        Long supplierId,
        String supplierCode,
        String supplierName,
        PurchaseOrderStatus status,
        OrderedProgress orderedProgress,
        LocalDate expectedDeliveryDate,
        @JsonInclude(JsonInclude.Include.ALWAYS)
        LocalDate supplierPromisedDeliveryDate,
        List<PurchaseOrderItemResponse> items,
        Long createdBy,
        String createdByDisplayName,
        String createdByUsername,
        Instant createdAt,
        Long approvedBy,
        String approvedByDisplayName,
        String approvedByUsername,
        Instant approvedAt,
        String rejectReason,
        Long rejectedBy,
        String rejectedByDisplayName,
        String rejectedByUsername,
        Instant rejectedAt,
        String cancelReason,
        Long cancelledBy,
        String cancelledByDisplayName,
        String cancelledByUsername,
        Instant cancelledAt,
        Long orderedBy,
        String orderedByDisplayName,
        String orderedByUsername,
        Instant orderedAt,
        Long receivedBy,
        String receivedByDisplayName,
        String receivedByUsername,
        Instant receivedAt,
        Long assignedTo,
        String assignedToDisplayName,
        String assignedToUsername,
        boolean isOverdue,
        Instant updatedAt
) { }
