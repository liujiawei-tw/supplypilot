package com.supplypilot.procurement.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

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

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "created_by", nullable = false)
    private User createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    @OneToMany(mappedBy = "purchaseOrder", cascade = CascadeType.PERSIST, orphanRemoval = true)
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

    public Long getId() { return id; }
    public String getPoNumber() { return poNumber; }
    public Supplier getSupplier() { return supplier; }
    public PurchaseOrderStatus getStatus() { return status; }
    public LocalDate getExpectedDeliveryDate() { return expectedDeliveryDate; }
    public User getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
    public List<PurchaseOrderItem> getItems() { return Collections.unmodifiableList(items); }
}
