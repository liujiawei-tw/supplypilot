package com.supplypilot.procurement.mapper;

import java.math.BigDecimal;

import com.supplypilot.procurement.domain.PurchaseOrder;
import com.supplypilot.procurement.domain.PurchaseOrderItem;
import com.supplypilot.procurement.dto.response.PurchaseOrderItemResponse;
import com.supplypilot.procurement.dto.response.PurchaseOrderResponse;
import org.springframework.stereotype.Component;

@Component
public class PurchaseOrderMapper {
    public PurchaseOrderResponse toResponse(PurchaseOrder purchaseOrder) {
        return new PurchaseOrderResponse(
                purchaseOrder.getId(), purchaseOrder.getPoNumber(), purchaseOrder.getSupplier().getId(),
                purchaseOrder.getSupplier().getCode(), purchaseOrder.getSupplier().getName(), purchaseOrder.getStatus(),
                purchaseOrder.getExpectedDeliveryDate(), purchaseOrder.getItems().stream().map(this::toItemResponse).toList(),
                purchaseOrder.getCreatedBy().getId(), purchaseOrder.getCreatedBy().getDisplayName(),
                purchaseOrder.getCreatedAt(), purchaseOrder.getUpdatedAt());
    }

    private PurchaseOrderItemResponse toItemResponse(PurchaseOrderItem item) {
        return new PurchaseOrderItemResponse(
                item.getId(), item.getMaterial().getId(), item.getMaterial().getCode(), item.getMaterial().getName(),
                item.getQuantity(), item.getUnit(), item.getUnitPrice(), item.getQuantity().multiply(item.getUnitPrice()),
                item.getRecommendationId(), item.getSupplierRecommendationResultId(),
                item.getSupplierOverrideReason() != null, item.getQuantityOverrideReason() != null);
    }
}
