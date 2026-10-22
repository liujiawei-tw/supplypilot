package com.supplypilot.procurement.dto.response;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.supplypilot.procurement.domain.PurchaseOrderStatus;
import com.supplypilot.procurement.domain.OrderedProgress;

/**
 * Whitelisted purchase-order view for warehouse receiving. Do not add procurement
 * pricing, recommendation, override, approval, or payment audit fields to this contract.
 */
public record ReceivingPurchaseOrderResponse(
        Long id,
        String poNumber,
        Long supplierId,
        String supplierCode,
        String supplierName,
        PurchaseOrderStatus status,
        OrderedProgress orderedProgress,
        ReceivingInspectionResponse receivingInspection,
        LocalDate expectedDeliveryDate,
        boolean isOverdue,
        List<ReceivingPurchaseOrderItemResponse> items,
        Instant orderedAt,
        Long receivedBy,
        String receivedByDisplayName,
        String receivedByUsername,
        Instant receivedAt
) { }
