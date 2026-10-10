package com.supplypilot.procurement.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.supplier.domain.Supplier;
import com.supplypilot.user.domain.User;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "purchase_orders")
public class PurchaseOrder {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "po_number", nullable = false, unique = true, length = 40)
    private String poNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PurchaseOrderStatus status;

    @Column(name = "expected_delivery_date")
    private LocalDate expectedDeliveryDate;

    @Column(name = "reject_reason", columnDefinition = "TEXT")
    private String rejectReason;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approved_by")
    private User approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rejected_by")
    private User rejectedBy;

    @Column(name = "rejected_at")
    private Instant rejectedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ordered_by")
    private User orderedBy;

    @Column(name = "ordered_at")
    private Instant orderedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "received_by")
    private User receivedBy;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    @OneToMany(mappedBy = "purchaseOrder", cascade = {CascadeType.PERSIST, CascadeType.MERGE}, orphanRemoval = true)
    @OrderBy("id ASC")
    private final List<PurchaseOrderItem> items = new ArrayList<>();

    protected PurchaseOrder() {
    }

    public static PurchaseOrder create(Supplier supplier, LocalDate expectedDeliveryDate, User createdBy, Instant now) {
        PurchaseOrder purchaseOrder = new PurchaseOrder();
        purchaseOrder.supplier = Objects.requireNonNull(supplier, "Supplier must not be null");
        purchaseOrder.expectedDeliveryDate = expectedDeliveryDate;
        purchaseOrder.createdBy = Objects.requireNonNull(createdBy, "Created by must not be null");
        purchaseOrder.status = PurchaseOrderStatus.DRAFT;
        purchaseOrder.createdAt = Objects.requireNonNull(now, "Timestamp must not be null");
        purchaseOrder.updatedAt = now;
        return purchaseOrder;
    }

    public void assignPendingPoNumber(String pendingPoNumber) {
        if (poNumber != null) throw new IllegalStateException("PO number has already been assigned");
        poNumber = Objects.requireNonNull(pendingPoNumber, "PO number must not be null");
    }

    public void assignDisplayPoNumber(String displayPoNumber) {
        if (id == null) throw new IllegalStateException("Purchase order must be persisted before assigning display number");
        poNumber = Objects.requireNonNull(displayPoNumber, "PO number must not be null");
    }

    public void addItem(PurchaseOrderItem item) {
        PurchaseOrderItem nonNullItem = Objects.requireNonNull(item, "Purchase order item must not be null");
        boolean duplicate = items.stream().anyMatch(existing -> existing.getMaterial() == nonNullItem.getMaterial()
                || (existing.getMaterial().getId() != null
                && existing.getMaterial().getId().equals(nonNullItem.getMaterial().getId())));
        if (duplicate) throw new BusinessException(ErrorCode.DUPLICATE_MATERIAL);
        nonNullItem.assignTo(this);
        items.add(nonNullItem);
    }

    public void editDraft(
            Supplier replacementSupplier,
            boolean replaceSupplier,
            LocalDate replacementExpectedDeliveryDate,
            boolean replaceExpectedDeliveryDate,
            List<PurchaseOrderItem> replacementItems,
            boolean replaceItems,
            Instant now) {
        if (status != PurchaseOrderStatus.DRAFT) throw new BusinessException(ErrorCode.PO_NOT_EDITABLE);

        if (replaceSupplier) supplier = Objects.requireNonNull(replacementSupplier, "Supplier must not be null");
        if (replaceExpectedDeliveryDate) expectedDeliveryDate = replacementExpectedDeliveryDate;
        if (replaceItems) replaceItems(replacementItems);
        if (replaceSupplier || replaceExpectedDeliveryDate || replaceItems) {
            updatedAt = Objects.requireNonNull(now, "Timestamp must not be null");
        }
    }

    public void approve(User approver, Instant now) {
        assertDraftTransitionTo(PurchaseOrderStatus.APPROVED);
        if (items.isEmpty()) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        User validatedApprover = Objects.requireNonNull(approver, "Approver must not be null");
        Instant validatedNow = Objects.requireNonNull(now, "Timestamp must not be null");
        status = PurchaseOrderStatus.APPROVED;
        approvedBy = validatedApprover;
        approvedAt = validatedNow;
        updatedAt = validatedNow;
    }

    public void reject(String reason, User rejector, Instant now) {
        assertDraftTransitionTo(PurchaseOrderStatus.REJECTED);
        if (reason == null || reason.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.REJECT_REASON_REQUIRED);
        }
        User validatedRejector = Objects.requireNonNull(rejector, "Rejector must not be null");
        Instant validatedNow = Objects.requireNonNull(now, "Timestamp must not be null");
        status = PurchaseOrderStatus.REJECTED;
        rejectReason = reason.trim();
        rejectedBy = validatedRejector;
        rejectedAt = validatedNow;
        updatedAt = validatedNow;
    }

    public void markOrdered(User orderer, Instant now) {
        if (status != PurchaseOrderStatus.APPROVED) {
            throw new BusinessException(ErrorCode.INVALID_PO_STATUS_TRANSITION);
        }
        if (expectedDeliveryDate == null) {
            throw new BusinessException(ErrorCode.EXPECTED_DELIVERY_DATE_REQUIRED);
        }
        if (supplier == null || items.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        for (PurchaseOrderItem item : items) {
            if (item.getMaterial() == null || item.getQuantity() == null
                    || item.getQuantity().signum() <= 0 || item.getUnitPrice() == null
                    || item.getUnitPrice().signum() < 0) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
        }
        User validatedOrderer = Objects.requireNonNull(orderer, "Orderer must not be null");
        Instant validatedNow = Objects.requireNonNull(now, "Timestamp must not be null");
        status = PurchaseOrderStatus.ORDERED;
        orderedBy = validatedOrderer;
        orderedAt = validatedNow;
        updatedAt = validatedNow;
    }

    public void validateReceivingEligibility() {
        if (status == PurchaseOrderStatus.RECEIVED) {
            throw new BusinessException(ErrorCode.DUPLICATE_RECEIVING);
        }
        if (status != PurchaseOrderStatus.ORDERED) {
            throw new BusinessException(ErrorCode.INVALID_PO_STATUS);
        }
        if (items.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        for (PurchaseOrderItem item : items) {
            if (item.getMaterial() == null || item.getMaterial().getBaseUnit() == null) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            if (item.getUnit() != item.getMaterial().getBaseUnit()) {
                throw new BusinessException(ErrorCode.UNIT_MISMATCH);
            }
            if (item.getQuantity() == null || item.getQuantity().signum() <= 0
                    || item.getQuantity().scale() > 4 || item.getQuantity().precision() > 19) {
                throw new BusinessException(ErrorCode.INVALID_QUANTITY);
            }
        }
    }

    public void receive(User receiver, Instant now) {
        validateReceivingEligibility();
        User validatedReceiver = Objects.requireNonNull(receiver, "Receiver must not be null");
        Instant validatedNow = Objects.requireNonNull(now, "Timestamp must not be null");
        status = PurchaseOrderStatus.RECEIVED;
        receivedBy = validatedReceiver;
        receivedAt = validatedNow;
        updatedAt = validatedNow;
    }

    public boolean isOverdue(LocalDate currentDate) {
        Objects.requireNonNull(currentDate, "Current date must not be null");
        return status == PurchaseOrderStatus.ORDERED
                && expectedDeliveryDate != null
                && currentDate.isAfter(expectedDeliveryDate);
    }

    void assertDraftTransitionTo(PurchaseOrderStatus target) {
        if (status != PurchaseOrderStatus.DRAFT
                || (target != PurchaseOrderStatus.APPROVED && target != PurchaseOrderStatus.REJECTED)) {
            throw new BusinessException(ErrorCode.INVALID_PO_STATUS_TRANSITION);
        }
    }

    private void replaceItems(List<PurchaseOrderItem> replacements) {
        if (replacements == null || replacements.isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        Set<Long> materialIds = replacements.stream()
                .map(item -> item.getMaterial().getId())
                .collect(Collectors.toSet());
        if (materialIds.size() != replacements.size()) throw new BusinessException(ErrorCode.DUPLICATE_MATERIAL);

        items.removeIf(existing -> !materialIds.contains(existing.getMaterial().getId()));
        for (PurchaseOrderItem replacement : replacements) {
            PurchaseOrderItem existing = items.stream()
                    .filter(item -> item.getMaterial().getId().equals(replacement.getMaterial().getId()))
                    .findFirst().orElse(null);
            if (existing == null) {
                replacement.assignTo(this);
                items.add(replacement);
            } else {
                existing.replaceBusinessDataFrom(replacement);
            }
        }
    }

    public Long getId() { return id; }
    public String getPoNumber() { return poNumber; }
    public Supplier getSupplier() { return supplier; }
    public PurchaseOrderStatus getStatus() { return status; }
    public LocalDate getExpectedDeliveryDate() { return expectedDeliveryDate; }
    public String getRejectReason() { return rejectReason; }
    public User getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public User getApprovedBy() { return approvedBy; }
    public Instant getApprovedAt() { return approvedAt; }
    public User getRejectedBy() { return rejectedBy; }
    public Instant getRejectedAt() { return rejectedAt; }
    public User getOrderedBy() { return orderedBy; }
    public Instant getOrderedAt() { return orderedAt; }
    public User getReceivedBy() { return receivedBy; }
    public Instant getReceivedAt() { return receivedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
    public List<PurchaseOrderItem> getItems() { return Collections.unmodifiableList(items); }
}
