package com.supplypilot.procurement.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.material.domain.BaseUnit;
import com.supplypilot.material.domain.Material;
import com.supplypilot.supplier.domain.Currency;

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

@Entity
@Table(name = "purchase_order_items")
public class PurchaseOrderItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "purchase_order_id", nullable = false)
    private PurchaseOrder purchaseOrder;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "material_id", nullable = false)
    private Material material;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Currency currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private BaseUnit unit;

    @Column(name = "recommendation_id")
    private Long recommendationId;

    @Column(name = "supplier_recommendation_result_id")
    private Long supplierRecommendationResultId;

    @Column(name = "recommended_supplier_id_snapshot")
    private Long recommendedSupplierIdSnapshot;

    @Column(name = "recommended_quantity_snapshot", precision = 19, scale = 4)
    private BigDecimal recommendedQuantitySnapshot;

    @Column(name = "supplier_override_reason", columnDefinition = "TEXT")
    private String supplierOverrideReason;

    @Column(name = "quantity_override_reason", columnDefinition = "TEXT")
    private String quantityOverrideReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PurchaseOrderItem() {
    }

    /** Compatibility factory for callers that predate line-level currency snapshots. */
    public static PurchaseOrderItem create(Material material, BigDecimal quantity, BigDecimal unitPrice, Instant now) {
        return create(material, quantity, unitPrice, Currency.TWD, now);
    }

    public static PurchaseOrderItem create(
            Material material, BigDecimal quantity, BigDecimal unitPrice, Currency currency, Instant now) {
        PurchaseOrderItem item = new PurchaseOrderItem();
        item.material = Objects.requireNonNull(material, "Material must not be null");
        item.quantity = validQuantity(quantity);
        item.unitPrice = validUnitPrice(unitPrice);
        item.currency = Objects.requireNonNull(currency, "Currency must not be null");
        item.unit = Objects.requireNonNull(material.getBaseUnit(), "Material base unit must not be null");
        item.createdAt = Objects.requireNonNull(now, "Timestamp must not be null");
        return item;
    }

    public static PurchaseOrderItem createFromRecommendation(
            Material material,
            BigDecimal quantity,
            BigDecimal unitPrice,
            Long recommendationId,
            Long supplierRecommendationResultId,
            Long recommendedSupplierIdSnapshot,
            BigDecimal recommendedQuantitySnapshot,
            String supplierOverrideReason,
            String quantityOverrideReason,
            Instant now) {
        return createFromRecommendation(material, quantity, unitPrice, Currency.TWD, recommendationId,
                supplierRecommendationResultId, recommendedSupplierIdSnapshot, recommendedQuantitySnapshot,
                supplierOverrideReason, quantityOverrideReason, now);
    }

    public static PurchaseOrderItem createFromRecommendation(
            Material material,
            BigDecimal quantity,
            BigDecimal unitPrice,
            Currency currency,
            Long recommendationId,
            Long supplierRecommendationResultId,
            Long recommendedSupplierIdSnapshot,
            BigDecimal recommendedQuantitySnapshot,
            String supplierOverrideReason,
            String quantityOverrideReason,
            Instant now) {
        PurchaseOrderItem item = create(material, quantity, unitPrice, currency, now);
        item.recommendationId = Objects.requireNonNull(recommendationId, "Recommendation id must not be null");
        item.supplierRecommendationResultId = Objects.requireNonNull(
                supplierRecommendationResultId, "Supplier recommendation result id must not be null");
        item.recommendedSupplierIdSnapshot = recommendedSupplierIdSnapshot;
        item.recommendedQuantitySnapshot = recommendedQuantitySnapshot;
        item.supplierOverrideReason = supplierOverrideReason;
        item.quantityOverrideReason = quantityOverrideReason;
        return item;
    }

    void assignTo(PurchaseOrder purchaseOrder) {
        if (this.purchaseOrder != null && this.purchaseOrder != purchaseOrder) {
            throw new IllegalStateException("Purchase order item already belongs to another purchase order");
        }
        this.purchaseOrder = Objects.requireNonNull(purchaseOrder, "Purchase order must not be null");
    }

    void replaceBusinessDataFrom(PurchaseOrderItem replacement) {
        if (!material.getId().equals(replacement.material.getId())) {
            throw new IllegalArgumentException("Replacement item must have the same material");
        }
        quantity = replacement.quantity;
        unitPrice = replacement.unitPrice;
        currency = replacement.currency;
        unit = replacement.unit;
        // Recommendation references and override details are historical snapshots. Draft edits
        // may update commercial values but cannot erase or rewrite the original decision record.
    }

    public void refreshCurrencySnapshot(Currency currency) {
        this.currency = Objects.requireNonNull(currency, "Currency must not be null");
    }

    private static BigDecimal validQuantity(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) throw new BusinessException(ErrorCode.INVALID_QUANTITY);
        return value;
    }

    private static BigDecimal validUnitPrice(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        return value;
    }

    public Long getId() { return id; }
    public PurchaseOrder getPurchaseOrder() { return purchaseOrder; }
    public Material getMaterial() { return material; }
    public BigDecimal getQuantity() { return quantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public Currency getCurrency() { return currency; }
    public BaseUnit getUnit() { return unit; }
    public Long getRecommendationId() { return recommendationId; }
    public Long getSupplierRecommendationResultId() { return supplierRecommendationResultId; }
    public Long getRecommendedSupplierIdSnapshot() { return recommendedSupplierIdSnapshot; }
    public BigDecimal getRecommendedQuantitySnapshot() { return recommendedQuantitySnapshot; }
    public String getSupplierOverrideReason() { return supplierOverrideReason; }
    public String getQuantityOverrideReason() { return quantityOverrideReason; }
    public Instant getCreatedAt() { return createdAt; }
}
