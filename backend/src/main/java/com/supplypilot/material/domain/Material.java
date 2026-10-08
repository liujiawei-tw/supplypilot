package com.supplypilot.material.domain;

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
@Table(name = "materials")
public class Material {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "base_unit", nullable = false, length = 10)
    private BaseUnit baseUnit;

    @Column(name = "safety_stock", nullable = false, precision = 19, scale = 4)
    private BigDecimal safetyStock;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MaterialStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Material() {
    }

    public static Material create(String code, String name, BaseUnit baseUnit, BigDecimal safetyStock, Instant now) {
        Objects.requireNonNull(code, "Code must not be null");
        Objects.requireNonNull(name, "Name must not be null");
        Objects.requireNonNull(baseUnit, "Base unit must not be null");
        Objects.requireNonNull(safetyStock, "Safety stock must not be null");
        Objects.requireNonNull(now, "Timestamp must not be null");

        String trimmedCode = code.trim();
        String trimmedName = name.trim();
        if (trimmedCode.isEmpty() || trimmedName.isEmpty() || safetyStock.compareTo(BigDecimal.ZERO) < 0) {
            throw new BusinessException(ErrorCode.VALIDATION_FAILED);
        }

        Material material = new Material();
        material.code = trimmedCode;
        material.name = trimmedName;
        material.baseUnit = baseUnit;
        material.safetyStock = safetyStock;
        material.status = MaterialStatus.ACTIVE;
        material.createdAt = now;
        material.updatedAt = now;
        return material;
    }

    public void update(String newName, BigDecimal newSafetyStock, MaterialStatus newStatus, Instant now) {
        Objects.requireNonNull(now, "Timestamp must not be null");
        boolean modified = false;
        if (newName != null) {
            String trimmedName = newName.trim();
            if (trimmedName.isEmpty()) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            if (!trimmedName.equals(name)) {
                name = trimmedName;
                modified = true;
            }
        }
        if (newSafetyStock != null) {
            if (newSafetyStock.compareTo(BigDecimal.ZERO) < 0) {
                throw new BusinessException(ErrorCode.VALIDATION_FAILED);
            }
            if (newSafetyStock.compareTo(safetyStock) != 0) {
                safetyStock = newSafetyStock;
                modified = true;
            }
        }
        if (newStatus != null && newStatus != status) {
            status = newStatus;
            modified = true;
        }
        if (modified) {
            updatedAt = now;
        }
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public BaseUnit getBaseUnit() { return baseUnit; }
    public BigDecimal getSafetyStock() { return safetyStock; }
    public MaterialStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
