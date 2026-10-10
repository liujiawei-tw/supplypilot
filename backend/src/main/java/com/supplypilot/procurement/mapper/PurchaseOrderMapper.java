package com.supplypilot.procurement.mapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;

import com.supplypilot.procurement.domain.PurchaseOrder;
import com.supplypilot.procurement.domain.PurchaseOrderItem;
import com.supplypilot.procurement.dto.response.PurchaseOrderItemResponse;
import com.supplypilot.procurement.dto.response.PurchaseOrderResponse;
import com.supplypilot.user.domain.User;
import org.springframework.stereotype.Component;

@Component
public class PurchaseOrderMapper {
    private final Clock clock;

    public PurchaseOrderMapper(Clock clock) {
        this.clock = clock;
    }

    public PurchaseOrderResponse toResponse(PurchaseOrder purchaseOrder) {
        return new PurchaseOrderResponse(
                purchaseOrder.getId(), purchaseOrder.getPoNumber(), purchaseOrder.getSupplier().getId(),
                purchaseOrder.getSupplier().getCode(), purchaseOrder.getSupplier().getName(), purchaseOrder.getStatus(),
                purchaseOrder.getExpectedDeliveryDate(), purchaseOrder.getItems().stream().map(this::toItemResponse).toList(),
                purchaseOrder.getCreatedBy().getId(), purchaseOrder.getCreatedBy().getDisplayName(),
                purchaseOrder.getCreatedAt(),
                userId(purchaseOrder.getApprovedBy()), userDisplayName(purchaseOrder.getApprovedBy()),
                purchaseOrder.getApprovedAt(), purchaseOrder.getRejectReason(),
                userId(purchaseOrder.getRejectedBy()), userDisplayName(purchaseOrder.getRejectedBy()),
                purchaseOrder.getRejectedAt(),
                userId(purchaseOrder.getOrderedBy()), userDisplayName(purchaseOrder.getOrderedBy()),
                purchaseOrder.getOrderedAt(), isOverdue(purchaseOrder), purchaseOrder.getUpdatedAt());
    }

    private boolean isOverdue(PurchaseOrder purchaseOrder) {
        return purchaseOrder.isOverdue(LocalDate.now(clock));
    }

    private Long userId(User user) {
        return user == null ? null : user.getId();
    }

    private String userDisplayName(User user) {
        return user == null ? null : user.getDisplayName();
    }

    private PurchaseOrderItemResponse toItemResponse(PurchaseOrderItem item) {
        return new PurchaseOrderItemResponse(
                item.getId(), item.getMaterial().getId(), item.getMaterial().getCode(), item.getMaterial().getName(),
                item.getQuantity(), item.getUnit(), item.getUnitPrice(), item.getQuantity().multiply(item.getUnitPrice()),
                item.getRecommendationId(), item.getSupplierRecommendationResultId(),
                item.getSupplierOverrideReason() != null, item.getQuantityOverrideReason() != null,
                item.getSupplierOverrideReason(), item.getQuantityOverrideReason());
    }
}
