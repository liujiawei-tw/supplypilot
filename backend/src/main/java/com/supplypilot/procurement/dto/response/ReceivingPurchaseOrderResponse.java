package com.supplypilot.procurement.dto.response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.supplypilot.procurement.domain.PurchaseOrderStatus;

/**
 * Whitelisted purchase-order view for warehouse receiving.  Do not add procurement
 * pricing, recommendation, override, or approval audit fields to this contract.
 */
public record ReceivingPurchaseOrderResponse(
        Long id,
        String poNumber,
        Long supplierId,
        String supplierCode,
        String supplierName,
        PurchaseOrderStatus status,
        LocalDate expectedDeliveryDate,
        boolean isOverdue,
        List<ReceivingPurchaseOrderItemResponse> items,
        Instant orderedAt,
        Long receivedBy,
        String receivedByDisplayName,
        Instant receivedAt
) { }
