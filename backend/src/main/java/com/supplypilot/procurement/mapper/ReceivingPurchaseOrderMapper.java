package com.supplypilot.procurement.mapper;

import java.time.Clock;
import java.time.LocalDate;

import com.supplypilot.procurement.domain.PurchaseOrder;
import com.supplypilot.procurement.domain.PurchaseOrderItem;
import com.supplypilot.procurement.dto.response.ReceivingPurchaseOrderItemResponse;
import com.supplypilot.procurement.dto.response.ReceivingPurchaseOrderResponse;
import com.supplypilot.user.domain.User;
import org.springframework.stereotype.Component;

@Component
public class ReceivingPurchaseOrderMapper {
    private final Clock clock;

    public ReceivingPurchaseOrderMapper(Clock clock) {
        this.clock = clock;
    }

    public ReceivingPurchaseOrderResponse toResponse(PurchaseOrder purchaseOrder) {
        return new ReceivingPurchaseOrderResponse(
                purchaseOrder.getId(), purchaseOrder.getPoNumber(), purchaseOrder.getSupplier().getId(),
                purchaseOrder.getSupplier().getCode(), purchaseOrder.getSupplier().getName(), purchaseOrder.getStatus(),
                purchaseOrder.getExpectedDeliveryDate(), purchaseOrder.isOverdue(LocalDate.now(clock)),
                purchaseOrder.getItems().stream().map(this::toItemResponse).toList(), purchaseOrder.getOrderedAt(),
                purchaseOrder.getReceivedByUserIdSnapshot(), purchaseOrder.getReceivedByDisplayNameSnapshot(),
                purchaseOrder.getReceivedByUsernameSnapshot(),
                purchaseOrder.getReceivedAt());
    }

    private ReceivingPurchaseOrderItemResponse toItemResponse(PurchaseOrderItem item) {
        return new ReceivingPurchaseOrderItemResponse(item.getId(), item.getMaterial().getId(),
                item.getMaterial().getCode(), item.getMaterial().getName(), item.getQuantity(), item.getUnit());
    }

    private Long userId(User user) {
        return user == null ? null : user.getId();
    }

    private String userDisplayName(User user) {
        return user == null ? null : user.getDisplayName();
    }
}
