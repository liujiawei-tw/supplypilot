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
        Instant updatedAt
) { }
