package com.supplypilot.procurement.mapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;

import com.supplypilot.procurement.domain.PurchaseOrder;
import com.supplypilot.procurement.domain.PurchaseOrderItem;
import com.supplypilot.procurement.dto.response.PurchaseOrderItemResponse;
import com.supplypilot.procurement.dto.response.PurchaseOrderResponse;
import com.supplypilot.procurement.dto.response.ReceivingInspectionResponse;
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
                purchaseOrder.getStage(),
                purchaseOrder.getOrderedProgress(),
                purchaseOrder.getExpectedDeliveryDate(), purchaseOrder.getSupplierPromisedDeliveryDate(),
                receivingInspection(purchaseOrder), purchaseOrder.isPaymentConfirmed(),
                purchaseOrder.getPaymentConfirmedByUserIdSnapshot(),
                purchaseOrder.getPaymentConfirmedByDisplayNameSnapshot(),
                purchaseOrder.getPaymentConfirmedByUsernameSnapshot(), purchaseOrder.getPaymentConfirmedAt(),
                purchaseOrder.getItems().stream().map(this::toItemResponse).toList(),
                purchaseOrder.getCreatedByUserIdSnapshot(), purchaseOrder.getCreatedByDisplayNameSnapshot(),
                purchaseOrder.getCreatedByUsernameSnapshot(),
                purchaseOrder.getCreatedAt(),
                purchaseOrder.getApprovedByUserIdSnapshot(), purchaseOrder.getApprovedByDisplayNameSnapshot(),
                purchaseOrder.getApprovedByUsernameSnapshot(),
                purchaseOrder.getApprovedAt(), purchaseOrder.getRejectReason(),
                purchaseOrder.getRejectedByUserIdSnapshot(), purchaseOrder.getRejectedByDisplayNameSnapshot(),
                purchaseOrder.getRejectedByUsernameSnapshot(),
                purchaseOrder.getRejectedAt(),
                purchaseOrder.getCancelReason(), purchaseOrder.getCancelledByUserIdSnapshot(),
                purchaseOrder.getCancelledByDisplayNameSnapshot(), purchaseOrder.getCancelledByUsernameSnapshot(),
                purchaseOrder.getCancelledAt(),
                purchaseOrder.getOrderedByUserIdSnapshot(), purchaseOrder.getOrderedByDisplayNameSnapshot(),
                purchaseOrder.getOrderedByUsernameSnapshot(),
                purchaseOrder.getOrderedAt(),
                purchaseOrder.getReceivedByUserIdSnapshot(), purchaseOrder.getReceivedByDisplayNameSnapshot(),
                purchaseOrder.getReceivedByUsernameSnapshot(), purchaseOrder.getReceivedAt(),
                userId(purchaseOrder.getAssignedTo()), userDisplayName(purchaseOrder.getAssignedTo()),
                purchaseOrder.getAssignedTo() == null ? null : purchaseOrder.getAssignedTo().getUsername(),
                isOverdue(purchaseOrder), purchaseOrder.getUpdatedAt());
    }

    private ReceivingInspectionResponse receivingInspection(PurchaseOrder purchaseOrder) {
        return new ReceivingInspectionResponse(purchaseOrder.isInspectionAllItemsReceived(),
                purchaseOrder.isInspectionAllQuantitiesConfirmed(), purchaseOrder.isInspectionAllQualityAccepted(),
                purchaseOrder.getInspectionNote(), purchaseOrder.getInspectedByUserIdSnapshot(),
                purchaseOrder.getInspectedByDisplayNameSnapshot(), purchaseOrder.getInspectedByUsernameSnapshot(),
                purchaseOrder.getInspectedAt());
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
