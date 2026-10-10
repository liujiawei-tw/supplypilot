package com.supplypilot.recommendation.domain;

import com.supplypilot.supplier.domain.Supplier;
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
import java.math.BigDecimal;
import java.util.Objects;

/** Candidate result retained as part of a {@link PurchaseRecommendation} snapshot. */
@Entity
@Table(name = "supplier_recommendation_result")
public class SupplierRecommendationResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recommendation_id", nullable = false)
    private PurchaseRecommendation recommendation;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @Column(name = "supplier_code_snapshot", nullable = false, length = 50)
    private String supplierCodeSnapshot;

    @Column(name = "supplier_name_snapshot", nullable = false, length = 100)
    private String supplierNameSnapshot;

    @Column(nullable = false)
    private boolean qualified;

    @Enumerated(EnumType.STRING)
    @Column(name = "disqualified_reason", length = 40)
    private DisqualificationReason disqualifiedReason;

    @Column(name = "recommended_quantity", precision = 19, scale = 4)
    private BigDecimal recommendedQuantity;

    @Column(name = "unit_price_snapshot", precision = 19, scale = 4)
    private BigDecimal unitPriceSnapshot;

    @Column(name = "total_cost", precision = 19, scale = 4)
    private BigDecimal totalCost;

    @Column(name = "lead_time_days_snapshot")
    private Integer leadTimeDaysSnapshot;

    @Column(name = "reliability_score_snapshot", precision = 5, scale = 2)
    private BigDecimal reliabilityScoreSnapshot;

    @Column(name = "quality_score_snapshot", precision = 5, scale = 2)
    private BigDecimal qualityScoreSnapshot;

    @Column(name = "price_score", precision = 15, scale = 12)
    private BigDecimal priceScore;

    @Column(name = "lead_time_score", precision = 15, scale = 12)
    private BigDecimal leadTimeScore;

    @Column(name = "reliability_score", precision = 15, scale = 12)
    private BigDecimal reliabilityScore;

    @Column(name = "quality_score", precision = 15, scale = 12)
    private BigDecimal qualityScore;

    @Column(name = "total_score", precision = 15, scale = 12)
    private BigDecimal totalScore;

    // RANK is a MySQL keyword; Hibernate preserves backtick quoting in physical SQL names.
    @Column(name = "`rank`")
    private Integer rank;

    protected SupplierRecommendationResult() {
    }

    public static SupplierRecommendationResult qualified(
            PurchaseRecommendation recommendation,
            Supplier supplier,
            BigDecimal recommendedQuantity,
            BigDecimal unitPriceSnapshot,
            BigDecimal totalCost,
            Integer leadTimeDaysSnapshot,
            BigDecimal reliabilityScoreSnapshot,
            BigDecimal qualityScoreSnapshot,
            BigDecimal priceScore,
            BigDecimal leadTimeScore,
            BigDecimal reliabilityScore,
            BigDecimal qualityScore,
            BigDecimal totalScore,
            Integer rank) {
        SupplierRecommendationResult result = base(recommendation, supplier, true);
        result.recommendedQuantity = required(recommendedQuantity, "Recommended quantity");
        result.unitPriceSnapshot = required(unitPriceSnapshot, "Unit price snapshot");
        result.totalCost = required(totalCost, "Total cost");
        result.leadTimeDaysSnapshot = required(leadTimeDaysSnapshot, "Lead time snapshot");
        result.reliabilityScoreSnapshot = required(reliabilityScoreSnapshot, "Reliability snapshot");
        result.qualityScoreSnapshot = required(qualityScoreSnapshot, "Quality snapshot");
        result.priceScore = required(priceScore, "Price score");
        result.leadTimeScore = required(leadTimeScore, "Lead time score");
        result.reliabilityScore = required(reliabilityScore, "Reliability score");
        result.qualityScore = required(qualityScore, "Quality score");
        result.totalScore = required(totalScore, "Total score");
        result.rank = required(rank, "Rank");
        if (result.rank < 1) {
            throw new IllegalArgumentException("Rank must be positive");
        }
        return result;
    }

    public static SupplierRecommendationResult disqualified(
            PurchaseRecommendation recommendation, Supplier supplier, DisqualificationReason reason) {
        SupplierRecommendationResult result = base(recommendation, supplier, false);
        result.disqualifiedReason = Objects.requireNonNull(reason, "Disqualification reason must not be null");
        return result;
    }

    private static SupplierRecommendationResult base(
            PurchaseRecommendation recommendation, Supplier supplier, boolean qualified) {
        SupplierRecommendationResult result = new SupplierRecommendationResult();
        result.recommendation = Objects.requireNonNull(recommendation, "Recommendation must not be null");
        result.supplier = Objects.requireNonNull(supplier, "Supplier must not be null");
        result.supplierCodeSnapshot = supplier.getCode();
        result.supplierNameSnapshot = supplier.getName();
        result.qualified = qualified;
        return result;
    }

    private static <T> T required(T value, String name) {
        return Objects.requireNonNull(value, name + " must not be null");
    }

    public Long getId() { return id; }
    public PurchaseRecommendation getRecommendation() { return recommendation; }
    public Supplier getSupplier() { return supplier; }
    public String getSupplierCodeSnapshot() { return supplierCodeSnapshot; }
    public String getSupplierNameSnapshot() { return supplierNameSnapshot; }
    public boolean isQualified() { return qualified; }
    public DisqualificationReason getDisqualifiedReason() { return disqualifiedReason; }
    public BigDecimal getRecommendedQuantity() { return recommendedQuantity; }
    public BigDecimal getUnitPriceSnapshot() { return unitPriceSnapshot; }
    public BigDecimal getTotalCost() { return totalCost; }
    public Integer getLeadTimeDaysSnapshot() { return leadTimeDaysSnapshot; }
    public BigDecimal getReliabilityScoreSnapshot() { return reliabilityScoreSnapshot; }
    public BigDecimal getQualityScoreSnapshot() { return qualityScoreSnapshot; }
    public BigDecimal getPriceScore() { return priceScore; }
    public BigDecimal getLeadTimeScore() { return leadTimeScore; }
    public BigDecimal getReliabilityScore() { return reliabilityScore; }
    public BigDecimal getQualityScore() { return qualityScore; }
    public BigDecimal getTotalScore() { return totalScore; }
    public Integer getRank() { return rank; }
}
