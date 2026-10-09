package com.supplypilot.inventory.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Objects;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.material.domain.Material;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "inventory")
public class Inventory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "material_id", nullable = false, unique = true)
    private Material material;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal quantity;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(nullable = false)
    private Long version;

    protected Inventory() {
    }

    public static Inventory create(Material material, Instant now) {
        Objects.requireNonNull(material, "Material must not be null");
        Objects.requireNonNull(now, "Timestamp must not be null");
        Inventory inventory = new Inventory();
        inventory.material = material;
        inventory.quantity = BigDecimal.ZERO;
        inventory.updatedAt = now;
        return inventory;
    }

    /**
     * Applies a signed quantity delta while preserving the inventory invariant that stock
     * can never become negative.
     */
    public void adjust(BigDecimal delta, Instant now) {
        Objects.requireNonNull(now, "Timestamp must not be null");
        BigDecimal validDelta = decimal19_4(delta);
        if (validDelta.signum() == 0) {
            throw new BusinessException(ErrorCode.INVALID_QUANTITY);
        }

        BigDecimal adjustedQuantity = decimal19_4(quantity.add(validDelta));
        if (adjustedQuantity.signum() < 0) {
            throw new BusinessException(ErrorCode.NEGATIVE_QUANTITY);
        }

        quantity = adjustedQuantity;
        updatedAt = now;
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
    public BigDecimal getQuantity() { return quantity; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}
