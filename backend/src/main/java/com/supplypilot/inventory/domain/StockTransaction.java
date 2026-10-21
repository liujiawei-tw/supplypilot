package com.supplypilot.inventory.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.material.domain.Material;
import com.supplypilot.user.domain.User;

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
import jakarta.persistence.Table;

/** Immutable audit record for inventory-changing business actions. */
@Entity
@Table(name = "stock_transactions")
public class StockTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "material_id", nullable = false)
    private Material material;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private StockTransactionType type;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    // Nullable because manual adjustments are not associated with a purchase order.
    @Column(name = "purchase_order_id")
    private Long purchaseOrderId;

    @Column(length = 500)
    private String reason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @Column(name = "created_by_user_id_snapshot")
    private Long createdByUserIdSnapshot;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected StockTransaction() {
    }

    public static StockTransaction manualAdjustment(
            Material material,
            BigDecimal quantity,
            String reason,
            User createdBy,
            Instant now
    ) {
        Objects.requireNonNull(material, "Material must not be null");
        Objects.requireNonNull(createdBy, "Created by must not be null");
        Objects.requireNonNull(now, "Timestamp must not be null");

        BigDecimal validQuantity = decimal19_4(quantity);
        if (validQuantity.signum() == 0) {
            throw new BusinessException(ErrorCode.INVALID_QUANTITY);
        }
        if (reason == null || reason.trim().isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        String trimmedReason = reason.trim();
        if (trimmedReason.length() > 500) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        StockTransaction transaction = new StockTransaction();
        transaction.material = material;
        transaction.type = StockTransactionType.MANUAL_ADJUSTMENT;
        transaction.quantity = validQuantity;
        transaction.purchaseOrderId = null;
        transaction.reason = trimmedReason;
        transaction.createdBy = createdBy;
        transaction.createdByUserIdSnapshot = createdBy.getId();
        transaction.createdAt = now;
        return transaction;
    }

    public static StockTransaction purchaseReceive(
            Material material,
            BigDecimal quantity,
            Long purchaseOrderId,
            User createdBy,
            Instant now
    ) {
        Objects.requireNonNull(material, "Material must not be null");
        Objects.requireNonNull(createdBy, "Created by must not be null");
        Objects.requireNonNull(now, "Timestamp must not be null");
        if (purchaseOrderId == null) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        BigDecimal validQuantity = decimal19_4(quantity);
        if (validQuantity.signum() <= 0) {
            throw new BusinessException(ErrorCode.INVALID_QUANTITY);
        }

        StockTransaction transaction = new StockTransaction();
        transaction.material = material;
        transaction.type = StockTransactionType.PURCHASE_RECEIVE;
        transaction.quantity = validQuantity;
        transaction.purchaseOrderId = purchaseOrderId;
        transaction.reason = null;
        transaction.createdBy = createdBy;
        transaction.createdByUserIdSnapshot = createdBy.getId();
        transaction.createdAt = now;
        return transaction;
    }

    private static BigDecimal decimal19_4(BigDecimal value) {
        if (value == null) {
            throw new BusinessException(ErrorCode.INVALID_QUANTITY);
        }
        try {
            BigDecimal normalized = value.setScale(4, RoundingMode.UNNECESSARY);
            if (normalized.precision() > 19) {
                throw new BusinessException(ErrorCode.INVALID_QUANTITY);
            }
            return normalized;
        } catch (ArithmeticException exception) {
            throw new BusinessException(ErrorCode.INVALID_QUANTITY);
        }
    }

    public Long getId() { return id; }
    public Material getMaterial() { return material; }
    public StockTransactionType getType() { return type; }
    public BigDecimal getQuantity() { return quantity; }
    public Long getPurchaseOrderId() { return purchaseOrderId; }
    public String getReason() { return reason; }
    public User getCreatedBy() { return createdBy; }
    public Long getCreatedByUserIdSnapshot() { return createdByUserIdSnapshot; }
    public Instant getCreatedAt() { return createdAt; }
}
