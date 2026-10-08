package com.supplypilot.supplier.domain;

import com.supplypilot.common.exception.BusinessException;
import com.supplypilot.common.exception.ErrorCode;
import com.supplypilot.material.domain.Material;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "supplier_materials", uniqueConstraints = @UniqueConstraint(name = "uk_supplier_materials_supplier_material", columnNames = {"supplier_id", "material_id"}))
public class SupplierMaterial {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "supplier_id", nullable = false) private Supplier supplier;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "material_id", nullable = false) private Material material;
    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4) private BigDecimal unitPrice;
    @Column(nullable = false, precision = 19, scale = 4) private BigDecimal moq;
    @Column(name = "package_multiple", precision = 19, scale = 4) private BigDecimal packageMultiple;
    @Column(name = "lead_time_days", nullable = false) private Integer leadTimeDays;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) private Currency currency;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private SupplierMaterialStatus status;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    protected SupplierMaterial() { }

    public static SupplierMaterial create(Supplier supplier, Material material, BigDecimal unitPrice, BigDecimal moq,
                                          BigDecimal packageMultiple, Integer leadTimeDays, Currency currency, Instant now) {
        SupplierMaterial result = new SupplierMaterial();
        result.supplier = Objects.requireNonNull(supplier, "Supplier must not be null");
        result.material = Objects.requireNonNull(material, "Material must not be null");
        result.unitPrice = validNonNegative(unitPrice);
        result.moq = validPositive(moq);
        result.packageMultiple = validOptionalPositive(packageMultiple);
        result.leadTimeDays = validLeadTime(leadTimeDays);
        result.currency = validCurrency(currency);
        result.status = SupplierMaterialStatus.ACTIVE;
        result.createdAt = Objects.requireNonNull(now, "Timestamp must not be null");
        result.updatedAt = now;
        return result;
    }

    public void update(BigDecimal newUnitPrice, BigDecimal newMoq, BigDecimal newPackageMultiple,
                       Integer newLeadTimeDays, SupplierMaterialStatus newStatus, Instant now) {
        Objects.requireNonNull(now, "Timestamp must not be null");
        boolean modified = false;
        if (newUnitPrice != null) { BigDecimal value = validNonNegative(newUnitPrice); if (value.compareTo(unitPrice) != 0) { unitPrice = value; modified = true; } }
        if (newMoq != null) { BigDecimal value = validPositive(newMoq); if (value.compareTo(moq) != 0) { moq = value; modified = true; } }
        if (newPackageMultiple != null) { BigDecimal value = validOptionalPositive(newPackageMultiple); if (!Objects.equals(value, packageMultiple)) { packageMultiple = value; modified = true; } }
        if (newLeadTimeDays != null) { Integer value = validLeadTime(newLeadTimeDays); if (!value.equals(leadTimeDays)) { leadTimeDays = value; modified = true; } }
        if (newStatus != null && newStatus != status) { status = newStatus; modified = true; }
        if (modified) updatedAt = now;
    }

    private static BigDecimal validNonNegative(BigDecimal value) { if (value == null || value.compareTo(BigDecimal.ZERO) < 0) throw new BusinessException(ErrorCode.VALIDATION_FAILED); return value; }
    private static BigDecimal validPositive(BigDecimal value) { if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) throw new BusinessException(ErrorCode.VALIDATION_FAILED); return value; }
    private static BigDecimal validOptionalPositive(BigDecimal value) { if (value != null && value.compareTo(BigDecimal.ZERO) <= 0) throw new BusinessException(ErrorCode.VALIDATION_FAILED); return value; }
    private static Integer validLeadTime(Integer value) { if (value == null || value < 0) throw new BusinessException(ErrorCode.VALIDATION_FAILED); return value; }
    private static Currency validCurrency(Currency value) { if (value != Currency.TWD) throw new BusinessException(ErrorCode.VALIDATION_FAILED); return value; }
    public Long getId() { return id; } public Supplier getSupplier() { return supplier; } public Material getMaterial() { return material; }
    public BigDecimal getUnitPrice() { return unitPrice; } public BigDecimal getMoq() { return moq; } public BigDecimal getPackageMultiple() { return packageMultiple; }
    public Integer getLeadTimeDays() { return leadTimeDays; } public Currency getCurrency() { return currency; } public SupplierMaterialStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; } public Instant getUpdatedAt() { return updatedAt; }
}
