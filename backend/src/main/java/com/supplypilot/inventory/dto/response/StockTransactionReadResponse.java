package com.supplypilot.inventory.dto.response;

import java.math.BigDecimal;
import java.time.Instant;

import com.supplypilot.inventory.domain.StockTransactionType;
import com.supplypilot.material.domain.BaseUnit;

/** Deliberately limited read projection; it does not expose purchase-order details. */
public record StockTransactionReadResponse(
        Long id, Long materialId, String materialCode, String materialName, BaseUnit unit,
        StockTransactionType type, BigDecimal quantity, Long purchaseOrderId,
        String purchaseOrderNumber, String reason, Long createdBy,
        String createdByDisplayName, Instant createdAt) {
}
