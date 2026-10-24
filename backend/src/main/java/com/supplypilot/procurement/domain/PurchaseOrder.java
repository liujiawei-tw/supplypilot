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
import jakarta.persistence.PrePersist;

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

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private PurchaseOrderStage stage;

    @Column(name = "expected_delivery_date")
    private LocalDate expectedDeliveryDate;

    @Column(name = "supplier_promised_delivery_date")
    private LocalDate supplierPromisedDeliveryDate;

    @Column(name = "inspection_all_items_received", nullable = false)
    private boolean inspectionAllItemsReceived;

    @Column(name = "inspection_all_quantities_confirmed", nullable = false)
    private boolean inspectionAllQuantitiesConfirmed;

    @Column(name = "inspection_all_quality_accepted", nullable = false)
    private boolean inspectionAllQualityAccepted;

    @Column(name = "inspection_note", columnDefinition = "TEXT")
    private String inspectionNote;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "inspected_by")
    private User inspectedBy;

    @Column(name = "inspected_by_user_id_snapshot") private Long inspectedByUserIdSnapshot;
    @Column(name = "inspected_by_username_snapshot", length = 50) private String inspectedByUsernameSnapshot;
    @Column(name = "inspected_by_display_name_snapshot", length = 100) private String inspectedByDisplayNameSnapshot;

    @Column(name = "inspected_at")
    private Instant inspectedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_confirmed_by")
    private User paymentConfirmedBy;

    @Column(name = "payment_confirmed_by_user_id_snapshot") private Long paymentConfirmedByUserIdSnapshot;
    @Column(name = "payment_confirmed_by_username_snapshot", length = 50) private String paymentConfirmedByUsernameSnapshot;
    @Column(name = "payment_confirmed_by_display_name_snapshot", length = 100) private String paymentConfirmedByDisplayNameSnapshot;

    @Column(name = "payment_confirmed_at")
    private Instant paymentConfirmedAt;

    @Column(name = "reject_reason", columnDefinition = "TEXT")
    private String rejectReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "ordered_progress", length = 30)
    private OrderedProgress orderedProgress;

    @Column(name = "cancel_reason", columnDefinition = "TEXT")
    private String cancelReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cancelled_by")
    private User cancelledBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assigned_to")
    private User assignedTo;

    @Column(name = "created_by_user_id_snapshot") private Long createdByUserIdSnapshot;
    @Column(name = "created_by_username_snapshot", length = 50) private String createdByUsernameSnapshot;
    @Column(name = "created_by_display_name_snapshot", length = 100) private String createdByDisplayNameSnapshot;
    @Column(name = "approved_by_user_id_snapshot") private Long approvedByUserIdSnapshot;
    @Column(name = "approved_by_username_snapshot", length = 50) private String approvedByUsernameSnapshot;
    @Column(name = "approved_by_display_name_snapshot", length = 100) private String approvedByDisplayNameSnapshot;
    @Column(name = "rejected_by_user_id_snapshot") private Long rejectedByUserIdSnapshot;
    @Column(name = "rejected_by_username_snapshot", length = 50) private String rejectedByUsernameSnapshot;
    @Column(name = "rejected_by_display_name_snapshot", length = 100) private String rejectedByDisplayNameSnapshot;
    @Column(name = "cancelled_by_user_id_snapshot") private Long cancelledByUserIdSnapshot;
    @Column(name = "cancelled_by_username_snapshot", length = 50) private String cancelledByUsernameSnapshot;
    @Column(name = "cancelled_by_display_name_snapshot", length = 100) private String cancelledByDisplayNameSnapshot;
    @Column(name = "ordered_by_user_id_snapshot") private Long orderedByUserIdSnapshot;
    @Column(name = "ordered_by_username_snapshot", length = 50) private String orderedByUsernameSnapshot;
    @Column(name = "ordered_by_display_name_snapshot", length = 100) private String orderedByDisplayNameSnapshot;
    @Column(name = "received_by_user_id_snapshot") private Long receivedByUserIdSnapshot;
    @Column(name = "received_by_username_snapshot", length = 50) private String receivedByUsernameSnapshot;
    @Column(name = "received_by_display_name_snapshot", length = 100) private String receivedByDisplayNameSnapshot;

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
        purchaseOrder.assignedTo = createdBy;
        purchaseOrder.createdByUserIdSnapshot = createdBy.getId();
        purchaseOrder.createdByUsernameSnapshot = createdBy.getUsername();
        purchaseOrder.createdByDisplayNameSnapshot = createdBy.getDisplayName();
        purchaseOrder.status = PurchaseOrderStatus.DRAFT;
        purchaseOrder.stage = PurchaseOrderStage.NEW_REQUEST;
        purchaseOrder.createdAt = Objects.requireNonNull(now, "Timestamp must not be null");
        purchaseOrder.updatedAt = now;
        return purchaseOrder;
    }

    @PrePersist
    private void captureCreator() {
        if (createdBy != null) {
            createdByUserIdSnapshot = createdBy.getId();
            createdByUsernameSnapshot = createdBy.getUsername();
            createdByDisplayNameSnapshot = createdBy.getDisplayName();
        }
    }

    public void assignTo(User assignee, Instant now) {
        if (status == PurchaseOrderStatus.REJECTED || status == PurchaseOrderStatus.CANCELLED) {
            throw new BusinessException(ErrorCode.INVALID_PO_STATUS_TRANSITION);
        }
        assignedTo = assignee;
        updatedAt = Objects.requireNonNull(now, "Timestamp must not be null");
    }

    private static String username(User actor) { return actor == null ? null : actor.getUsername(); }
    private static String displayName(User actor) { return actor == null ? null : actor.getDisplayName(); }

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
        requireStage(PurchaseOrderStage.APPROVAL);
        if (items.isEmpty()) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        if (expectedDeliveryDate == null) {
            throw new BusinessException(ErrorCode.EXPECTED_DELIVERY_DATE_REQUIRED);
        }
        User validatedApprover = Objects.requireNonNull(approver, "Approver must not be null");
        Instant validatedNow = Objects.requireNonNull(now, "Timestamp must not be null");
        status = PurchaseOrderStatus.APPROVED;
        stage = PurchaseOrderStage.APPROVED_PENDING_ORDER;
        approvedBy = validatedApprover;
        approvedAt = validatedNow;
        approvedByUserIdSnapshot = validatedApprover.getId();
        approvedByUsernameSnapshot = username(validatedApprover);
        approvedByDisplayNameSnapshot = displayName(validatedApprover);
        updatedAt = validatedNow;
    }

    public void updateSupplierPromisedDeliveryDate(LocalDate supplierPromisedDeliveryDate, Instant now) {
        if (status != PurchaseOrderStatus.APPROVED && status != PurchaseOrderStatus.ORDERED) {
            throw new BusinessException(ErrorCode.INVALID_PO_STATUS_TRANSITION);
        }
        this.supplierPromisedDeliveryDate = supplierPromisedDeliveryDate;
        updatedAt = Objects.requireNonNull(now, "Timestamp must not be null");
    }

    public void reject(String reason, User rejector, Instant now) {
        assertDraftTransitionTo(PurchaseOrderStatus.REJECTED);
        requireDraftStage();
        if (reason == null || reason.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.REJECT_REASON_REQUIRED);
        }
        User validatedRejector = Objects.requireNonNull(rejector, "Rejector must not be null");
        Instant validatedNow = Objects.requireNonNull(now, "Timestamp must not be null");
        status = PurchaseOrderStatus.REJECTED;
        stage = null;
        rejectReason = reason.trim();
        rejectedBy = validatedRejector;
        rejectedAt = validatedNow;
        rejectedByUserIdSnapshot = validatedRejector.getId();
        rejectedByUsernameSnapshot = username(validatedRejector);
        rejectedByDisplayNameSnapshot = displayName(validatedRejector);
        updatedAt = validatedNow;
    }

    public void markOrdered(User orderer, Instant now) {
        if (status != PurchaseOrderStatus.APPROVED) {
            throw new BusinessException(ErrorCode.INVALID_PO_STATUS_TRANSITION);
        }
        requireStage(PurchaseOrderStage.APPROVED_PENDING_ORDER);
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
        stage = PurchaseOrderStage.ORDER_FOLLOW_UP;
        orderedProgress = OrderedProgress.FOLLOW_UP;
        orderedBy = validatedOrderer;
        orderedAt = validatedNow;
        orderedByUserIdSnapshot = validatedOrderer.getId();
        orderedByUsernameSnapshot = username(validatedOrderer);
        orderedByDisplayNameSnapshot = displayName(validatedOrderer);
        updatedAt = validatedNow;
    }

    public void beginReceivingInspection(Instant now) {
        Instant validatedNow = Objects.requireNonNull(now, "Timestamp must not be null");
        if (status != PurchaseOrderStatus.ORDERED) {
            throw new BusinessException(ErrorCode.INVALID_PO_STATUS_TRANSITION);
        }

        // V25 deliberately left the position of pre-existing ordered rows unknown.
        // The ADMIN-only command is the explicit disposition into inspection; it
        // does not infer which stage the row occupied before this command.
        if (orderedProgress == OrderedProgress.LEGACY_UNKNOWN && stage == null) {
            orderedProgress = OrderedProgress.RECEIVING_INSPECTION;
            stage = PurchaseOrderStage.RECEIVING_INSPECTION;
            updatedAt = validatedNow;
            return;
        }

        if (orderedProgress != OrderedProgress.FOLLOW_UP || stage != PurchaseOrderStage.ORDER_FOLLOW_UP) {
            throw new BusinessException(ErrorCode.INVALID_PO_STATUS_TRANSITION);
        }
        orderedProgress = OrderedProgress.RECEIVING_INSPECTION;
        stage = PurchaseOrderStage.RECEIVING_INSPECTION;
        updatedAt = validatedNow;
    }

    public void recordReceivingInspection(boolean allItemsReceived, boolean allQuantitiesConfirmed,
            boolean allQualityAccepted, String note, User inspector, Instant now) {
        if (status != PurchaseOrderStatus.ORDERED || orderedProgress != OrderedProgress.RECEIVING_INSPECTION
                || stage != PurchaseOrderStage.RECEIVING_INSPECTION) {
            throw new BusinessException(ErrorCode.INVALID_PO_STATUS_TRANSITION);
        }
        String normalizedNote = note == null || note.isBlank() ? null : note.trim();
        if (normalizedNote != null && normalizedNote.length() > 2000) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        User validatedInspector = Objects.requireNonNull(inspector, "Inspector must not be null");
        Instant validatedNow = Objects.requireNonNull(now, "Timestamp must not be null");
        inspectionAllItemsReceived = allItemsReceived;
        inspectionAllQuantitiesConfirmed = allQuantitiesConfirmed;
        inspectionAllQualityAccepted = allQualityAccepted;
        inspectionNote = normalizedNote;
        inspectedBy = validatedInspector;
        inspectedByUserIdSnapshot = validatedInspector.getId();
        inspectedByUsernameSnapshot = username(validatedInspector);
        inspectedByDisplayNameSnapshot = displayName(validatedInspector);
        inspectedAt = validatedNow;
        updatedAt = validatedNow;
    }

    public void confirmPayment(User confirmer, Instant now) {
        if (status != PurchaseOrderStatus.RECEIVED || stage != PurchaseOrderStage.PAYMENT_RECONCILIATION
                || paymentConfirmedAt != null) {
            throw new BusinessException(ErrorCode.INVALID_PO_STATUS_TRANSITION);
        }
        User validatedConfirmer = Objects.requireNonNull(confirmer, "Payment confirmer must not be null");
        Instant validatedNow = Objects.requireNonNull(now, "Timestamp must not be null");
        paymentConfirmedBy = validatedConfirmer;
        paymentConfirmedByUserIdSnapshot = validatedConfirmer.getId();
        paymentConfirmedByUsernameSnapshot = username(validatedConfirmer);
        paymentConfirmedByDisplayNameSnapshot = displayName(validatedConfirmer);
        paymentConfirmedAt = validatedNow;
        stage = PurchaseOrderStage.COMPLETED;
        updatedAt = validatedNow;
    }

    public void revokePaymentConfirmation(Instant now) {
        if (status != PurchaseOrderStatus.RECEIVED || stage != PurchaseOrderStage.COMPLETED
                || paymentConfirmedAt == null) {
            throw new BusinessException(ErrorCode.INVALID_PO_STATUS_TRANSITION);
        }
        paymentConfirmedBy = null;
        paymentConfirmedByUserIdSnapshot = null;
        paymentConfirmedByUsernameSnapshot = null;
        paymentConfirmedByDisplayNameSnapshot = null;
        paymentConfirmedAt = null;
        stage = PurchaseOrderStage.PAYMENT_RECONCILIATION;
        updatedAt = Objects.requireNonNull(now, "Timestamp must not be null");
    }

    public void cancel(String reason, User actor, Instant now) {
        if (status != PurchaseOrderStatus.APPROVED
                && !(status == PurchaseOrderStatus.ORDERED && orderedProgress == OrderedProgress.FOLLOW_UP)) {
            throw new BusinessException(ErrorCode.INVALID_PO_STATUS_TRANSITION);
        }
        if ((status == PurchaseOrderStatus.APPROVED && stage != PurchaseOrderStage.APPROVED_PENDING_ORDER)
                || (status == PurchaseOrderStatus.ORDERED && stage != PurchaseOrderStage.ORDER_FOLLOW_UP)) {
            throw new BusinessException(ErrorCode.INVALID_PO_STATUS_TRANSITION);
        }
        if (reason == null || reason.trim().isEmpty()) throw new BusinessException(ErrorCode.CANCEL_REASON_REQUIRED);
        String normalizedReason = reason.trim();
        if (normalizedReason.length() > 2000) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        User validatedActor = Objects.requireNonNull(actor, "Cancelling actor must not be null");
        Instant validatedNow = Objects.requireNonNull(now, "Timestamp must not be null");
        status = PurchaseOrderStatus.CANCELLED;
        stage = null;
        orderedProgress = null;
        cancelReason = normalizedReason;
        cancelledBy = validatedActor;
        cancelledAt = validatedNow;
        cancelledByUserIdSnapshot = validatedActor.getId();
        cancelledByUsernameSnapshot = username(validatedActor);
        cancelledByDisplayNameSnapshot = displayName(validatedActor);
        updatedAt = validatedNow;
    }

    public void validateReceivingEligibility() {
        if (status == PurchaseOrderStatus.RECEIVED) {
            throw new BusinessException(ErrorCode.DUPLICATE_RECEIVING);
        }
        if (status != PurchaseOrderStatus.ORDERED) {
            throw new BusinessException(ErrorCode.INVALID_PO_STATUS);
        }
        if (orderedProgress != OrderedProgress.RECEIVING_INSPECTION
                || stage != PurchaseOrderStage.RECEIVING_INSPECTION
                || !inspectionAllItemsReceived || !inspectionAllQuantitiesConfirmed || !inspectionAllQualityAccepted
                || inspectedAt == null || (inspectedBy == null && inspectedByUserIdSnapshot == null)) {
            throw new BusinessException(ErrorCode.RECEIVING_INSPECTION_INCOMPLETE);
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
        stage = PurchaseOrderStage.PAYMENT_RECONCILIATION;
        orderedProgress = null;
        receivedBy = validatedReceiver;
        receivedAt = validatedNow;
        receivedByUserIdSnapshot = validatedReceiver.getId();
        receivedByUsernameSnapshot = username(validatedReceiver);
        receivedByDisplayNameSnapshot = displayName(validatedReceiver);
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

    public void advanceFromNewRequest(Instant now) {
        advancePreApprovalStage(PurchaseOrderStage.NEW_REQUEST, PurchaseOrderStage.SOURCING, now);
    }

    public void advanceFromSourcing(Instant now) {
        advancePreApprovalStage(PurchaseOrderStage.SOURCING, PurchaseOrderStage.APPROVAL, now);
    }

    private void advancePreApprovalStage(PurchaseOrderStage expected, PurchaseOrderStage next, Instant now) {
        if (status != PurchaseOrderStatus.DRAFT || stage != expected) {
            throw new BusinessException(ErrorCode.INVALID_PO_STATUS_TRANSITION);
        }
        stage = next;
        updatedAt = Objects.requireNonNull(now, "Timestamp must not be null");
    }

    private void requireDraftStage() {
        if (status != PurchaseOrderStatus.DRAFT || stage == null
                || (stage != PurchaseOrderStage.NEW_REQUEST
                && stage != PurchaseOrderStage.SOURCING
                && stage != PurchaseOrderStage.APPROVAL)) {
            throw new BusinessException(ErrorCode.INVALID_PO_STATUS_TRANSITION);
        }
    }

    private void requireStage(PurchaseOrderStage expected) {
        if (stage != expected || expected.getPurchaseOrderStatus() != status) {
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
    public PurchaseOrderStage getStage() { return stage; }
    public LocalDate getExpectedDeliveryDate() { return expectedDeliveryDate; }
    public LocalDate getSupplierPromisedDeliveryDate() { return supplierPromisedDeliveryDate; }
    public String getRejectReason() { return rejectReason; }
    public OrderedProgress getOrderedProgress() { return orderedProgress; }
    public String getCancelReason() { return cancelReason; }
    public Long getCancelledByUserIdSnapshot() { return cancelledByUserIdSnapshot; }
    public String getCancelledByUsernameSnapshot() { return cancelledByUsernameSnapshot; }
    public String getCancelledByDisplayNameSnapshot() { return cancelledByDisplayNameSnapshot; }
    public Instant getCancelledAt() { return cancelledAt; }
    public User getCreatedBy() { return createdBy; }
    public User getAssignedTo() { return assignedTo; }
    public Long getCreatedByUserIdSnapshot() { return createdByUserIdSnapshot; }
    public String getCreatedByUsernameSnapshot() { return createdByUsernameSnapshot; }
    public String getCreatedByDisplayNameSnapshot() { return createdByDisplayNameSnapshot; }
    public Long getApprovedByUserIdSnapshot() { return approvedByUserIdSnapshot; }
    public String getApprovedByUsernameSnapshot() { return approvedByUsernameSnapshot; }
    public String getApprovedByDisplayNameSnapshot() { return approvedByDisplayNameSnapshot; }
    public Long getRejectedByUserIdSnapshot() { return rejectedByUserIdSnapshot; }
    public String getRejectedByUsernameSnapshot() { return rejectedByUsernameSnapshot; }
    public String getRejectedByDisplayNameSnapshot() { return rejectedByDisplayNameSnapshot; }
    public Long getOrderedByUserIdSnapshot() { return orderedByUserIdSnapshot; }
    public String getOrderedByUsernameSnapshot() { return orderedByUsernameSnapshot; }
    public String getOrderedByDisplayNameSnapshot() { return orderedByDisplayNameSnapshot; }
    public Long getReceivedByUserIdSnapshot() { return receivedByUserIdSnapshot; }
    public String getReceivedByUsernameSnapshot() { return receivedByUsernameSnapshot; }
    public String getReceivedByDisplayNameSnapshot() { return receivedByDisplayNameSnapshot; }
    public Instant getCreatedAt() { return createdAt; }
    public User getApprovedBy() { return approvedBy; }
    public Instant getApprovedAt() { return approvedAt; }
    public User getRejectedBy() { return rejectedBy; }
    public Instant getRejectedAt() { return rejectedAt; }
    public User getOrderedBy() { return orderedBy; }
    public Instant getOrderedAt() { return orderedAt; }
    public User getReceivedBy() { return receivedBy; }
    public Instant getReceivedAt() { return receivedAt; }
    public boolean isInspectionAllItemsReceived() { return inspectionAllItemsReceived; }
    public boolean isInspectionAllQuantitiesConfirmed() { return inspectionAllQuantitiesConfirmed; }
    public boolean isInspectionAllQualityAccepted() { return inspectionAllQualityAccepted; }
    public String getInspectionNote() { return inspectionNote; }
    public Long getInspectedByUserIdSnapshot() { return inspectedByUserIdSnapshot; }
    public String getInspectedByUsernameSnapshot() { return inspectedByUsernameSnapshot; }
    public String getInspectedByDisplayNameSnapshot() { return inspectedByDisplayNameSnapshot; }
    public Instant getInspectedAt() { return inspectedAt; }
    public boolean isPaymentConfirmed() { return paymentConfirmedAt != null; }
    public Long getPaymentConfirmedByUserIdSnapshot() { return paymentConfirmedByUserIdSnapshot; }
    public String getPaymentConfirmedByUsernameSnapshot() { return paymentConfirmedByUsernameSnapshot; }
    public String getPaymentConfirmedByDisplayNameSnapshot() { return paymentConfirmedByDisplayNameSnapshot; }
    public Instant getPaymentConfirmedAt() { return paymentConfirmedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
    public List<PurchaseOrderItem> getItems() { return Collections.unmodifiableList(items); }
}
