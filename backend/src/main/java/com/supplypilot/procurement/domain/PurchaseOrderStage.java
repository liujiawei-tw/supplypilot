package com.supplypilot.procurement.domain;

/** The durable Kanban position, independent from the purchase-order lifecycle status. */
public enum PurchaseOrderStage {
    NEW_REQUEST(PurchaseOrderStatus.DRAFT),
    SOURCING(PurchaseOrderStatus.DRAFT),
    APPROVAL(PurchaseOrderStatus.DRAFT),
    APPROVED_PENDING_ORDER(PurchaseOrderStatus.APPROVED),
    ORDER_FOLLOW_UP(PurchaseOrderStatus.ORDERED),
    RECEIVING_INSPECTION(PurchaseOrderStatus.ORDERED),
    PAYMENT_RECONCILIATION(PurchaseOrderStatus.RECEIVED),
    COMPLETED(PurchaseOrderStatus.RECEIVED);

    private final PurchaseOrderStatus purchaseOrderStatus;

    PurchaseOrderStage(PurchaseOrderStatus purchaseOrderStatus) {
        this.purchaseOrderStatus = purchaseOrderStatus;
    }

    public PurchaseOrderStatus getPurchaseOrderStatus() {
        return purchaseOrderStatus;
    }
}
