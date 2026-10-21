package com.supplypilot.recommendation.domain;

import com.supplypilot.material.domain.Material;
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
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable historical calculation snapshot aggregate root. */
@Entity
@Table(name = "purchase_recommendation")
public class PurchaseRecommendation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "material_id", nullable = false)
    private Material material;

    @Column(name = "material_code_snapshot", nullable = false, length = 50)
    private String materialCodeSnapshot;

    @Column(name = "material_name_snapshot", nullable = false, length = 100)
    private String materialNameSnapshot;

    @Column(name = "period_start", nullable = false)
    private LocalDate periodStart;

    @Column(name = "period_end", nullable = false)
    private LocalDate periodEnd;

    @Column(name = "expected_demand", nullable = false, precision = 19, scale = 4)
    private BigDecimal expectedDemand;

    @Column(name = "safety_stock", nullable = false, precision = 19, scale = 4)
    private BigDecimal safetyStock;

    @Column(name = "current_inventory", nullable = false, precision = 19, scale = 4)
    private BigDecimal currentInventory;

    @Column(name = "incoming_inventory", nullable = false, precision = 19, scale = 4)
    private BigDecimal incomingInventory;

    @Column(name = "net_requirement", nullable = false, precision = 19, scale = 4)
    private BigDecimal netRequirement;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private RecommendationOutcome outcome;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recommended_supplier_id")
    private Supplier recommendedSupplier;

    @Column(name = "recommended_quantity", precision = 19, scale = 4)
    private BigDecimal recommendedQuantity;

    @Column(name = "recommended_supplier_score", precision = 17, scale = 14)
    private BigDecimal recommendedSupplierScore;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private User createdBy;

    @Column(name = "created_by_user_id_snapshot")
    private Long createdByUserIdSnapshot;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "recommendation", cascade = CascadeType.PERSIST, orphanRemoval = true)
    private List<SupplierRecommendationResult> supplierResults = new ArrayList<>();

    protected PurchaseRecommendation() {
    }

    public static PurchaseRecommendation create(
            Material material,
            LocalDate periodStart,
            LocalDate periodEnd,
            BigDecimal expectedDemand,
            BigDecimal safetyStock,
            BigDecimal currentInventory,
            BigDecimal incomingInventory,
            BigDecimal netRequirement,
            RecommendationOutcome outcome,
            Supplier recommendedSupplier,
            BigDecimal recommendedQuantity,
            BigDecimal recommendedSupplierScore,
            User createdBy,
            Instant createdAt) {
        PurchaseRecommendation recommendation = new PurchaseRecommendation();
        recommendation.material = Objects.requireNonNull(material, "Material must not be null");
        recommendation.materialCodeSnapshot = material.getCode();
        recommendation.materialNameSnapshot = material.getName();
        recommendation.periodStart = Objects.requireNonNull(periodStart, "Period start must not be null");
        recommendation.periodEnd = Objects.requireNonNull(periodEnd, "Period end must not be null");
        if (periodEnd.isBefore(periodStart)) {
            throw new IllegalArgumentException("Period end must not precede period start");
        }
        recommendation.expectedDemand = nonNegative(expectedDemand, "Expected demand");
        recommendation.safetyStock = nonNegative(safetyStock, "Safety stock");
        recommendation.currentInventory = nonNegative(currentInventory, "Current inventory");
        recommendation.incomingInventory = nonNegative(incomingInventory, "Incoming inventory");
        recommendation.netRequirement = nonNegative(netRequirement, "Net requirement");
        recommendation.outcome = Objects.requireNonNull(outcome, "Outcome must not be null");
        recommendation.recommendedSupplier = recommendedSupplier;
        recommendation.recommendedQuantity = recommendedQuantity;
        recommendation.recommendedSupplierScore = recommendedSupplierScore;
        recommendation.createdBy = Objects.requireNonNull(createdBy, "Created by must not be null");
        recommendation.createdByUserIdSnapshot = createdBy.getId();
        recommendation.createdAt = Objects.requireNonNull(createdAt, "Created at must not be null");
        recommendation.validateOutcome();
        return recommendation;
    }

    public void addSupplierResult(SupplierRecommendationResult result) {
        Objects.requireNonNull(result, "Supplier result must not be null");
        if (result.getRecommendation() != this) {
            throw new IllegalArgumentException("Supplier result must belong to this recommendation");
        }
        supplierResults.add(result);
    }

    private void validateOutcome() {
        if (outcome == RecommendationOutcome.PURCHASE_NOT_REQUIRED && netRequirement.signum() != 0) {
            throw new IllegalArgumentException("Purchase not required requires zero net requirement");
        }
        if (outcome == RecommendationOutcome.SUPPLIER_RECOMMENDED
                && (netRequirement.signum() <= 0 || recommendedSupplier == null
                || recommendedQuantity == null || recommendedSupplierScore == null)) {
            throw new IllegalArgumentException("Supplier recommendation requires a selected supplier snapshot");
        }
        if (outcome != RecommendationOutcome.SUPPLIER_RECOMMENDED
                && (recommendedSupplier != null || recommendedQuantity != null || recommendedSupplierScore != null)) {
            throw new IllegalArgumentException("Non-recommended outcomes must not have a selected supplier snapshot");
        }
    }

    private static BigDecimal nonNegative(BigDecimal value, String name) {
        Objects.requireNonNull(value, name + " must not be null");
        if (value.signum() < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return value;
    }

    public Long getId() { return id; }
    public Material getMaterial() { return material; }
    public String getMaterialCodeSnapshot() { return materialCodeSnapshot; }
    public String getMaterialNameSnapshot() { return materialNameSnapshot; }
    public LocalDate getPeriodStart() { return periodStart; }
    public LocalDate getPeriodEnd() { return periodEnd; }
    public BigDecimal getExpectedDemand() { return expectedDemand; }
    public BigDecimal getSafetyStock() { return safetyStock; }
    public BigDecimal getCurrentInventory() { return currentInventory; }
    public BigDecimal getIncomingInventory() { return incomingInventory; }
    public BigDecimal getNetRequirement() { return netRequirement; }
    public RecommendationOutcome getOutcome() { return outcome; }
    public Supplier getRecommendedSupplier() { return recommendedSupplier; }
    public BigDecimal getRecommendedQuantity() { return recommendedQuantity; }
    public BigDecimal getRecommendedSupplierScore() { return recommendedSupplierScore; }
    public User getCreatedBy() { return createdBy; }
    public Long getCreatedByUserIdSnapshot() { return createdByUserIdSnapshot; }
    public Instant getCreatedAt() { return createdAt; }
    public List<SupplierRecommendationResult> getSupplierResults() { return List.copyOf(supplierResults); }
}
