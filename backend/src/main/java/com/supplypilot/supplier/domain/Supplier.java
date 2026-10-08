package com.supplypilot.supplier.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "suppliers")
public class Supplier {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, length = 50)
    private String code;
    @Column(nullable = false, length = 100)
    private String name;
    @Column(length = 100)
    private String country;
    @Column(name = "reliability_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal reliabilityScore;
    @Column(name = "quality_score", nullable = false, precision = 5, scale = 2)
    private BigDecimal qualityScore;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20)
    private SupplierStatus status;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Supplier() { }

    public static Supplier create(String code, String name, String country, BigDecimal reliabilityScore,
                                  BigDecimal qualityScore, Instant now) {
        requireTimestamp(now);
        Supplier supplier = new Supplier();
        supplier.code = requiredText(code);
        supplier.name = requiredText(name);
        supplier.country = normalizeCountry(country);
        supplier.reliabilityScore = validScore(reliabilityScore);
        supplier.qualityScore = validScore(qualityScore);
        supplier.status = SupplierStatus.ACTIVE;
        supplier.createdAt = now;
        supplier.updatedAt = now;
        return supplier;
    }

    /** Null inputs retain their existing value; an empty country explicitly clears it. */
    public void update(String newName, String newCountry, BigDecimal newReliabilityScore,
                       BigDecimal newQualityScore, SupplierStatus newStatus, Instant now) {
        requireTimestamp(now);
        boolean modified = false;
        if (newName != null) {
            String normalized = requiredText(newName);
            if (!normalized.equals(name)) { name = normalized; modified = true; }
        }
        if (newCountry != null) {
            String normalized = normalizeCountry(newCountry);
            if (!Objects.equals(normalized, country)) { country = normalized; modified = true; }
        }
        if (newReliabilityScore != null && newReliabilityScore.compareTo(reliabilityScore) != 0) {
            reliabilityScore = validScore(newReliabilityScore); modified = true;
        } else if (newReliabilityScore != null) { validScore(newReliabilityScore); }
        if (newQualityScore != null && newQualityScore.compareTo(qualityScore) != 0) {
            qualityScore = validScore(newQualityScore); modified = true;
        } else if (newQualityScore != null) { validScore(newQualityScore); }
        if (newStatus != null && newStatus != status) { status = newStatus; modified = true; }
        if (modified) updatedAt = now;
    }

    private static String requiredText(String value) {
        if (value == null || value.trim().isEmpty()) throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        return value.trim();
    }
    private static String normalizeCountry(String value) {
        if (value == null || value.trim().isEmpty()) return null;
        return value.trim();
    }
    private static BigDecimal validScore(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(new BigDecimal("100")) > 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }
        return value;
    }
    private static void requireTimestamp(Instant now) { Objects.requireNonNull(now, "Timestamp must not be null"); }
    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getCountry() { return country; }
    public BigDecimal getReliabilityScore() { return reliabilityScore; }
    public BigDecimal getQualityScore() { return qualityScore; }
    public SupplierStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
