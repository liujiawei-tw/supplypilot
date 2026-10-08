package com.supplypilot.supplier.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.supplypilot.supplier.domain.Currency;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public class CreateSupplierMaterialRequest {
    @NotNull private Long supplierId; @NotNull private Long materialId;
    @NotNull @DecimalMin("0.0") private BigDecimal unitPrice;
    @NotNull @DecimalMin(value = "0.0", inclusive = false) private BigDecimal moq;
    @DecimalMin(value = "0.0", inclusive = false) private BigDecimal packageMultiple;
    @NotNull @Min(0) private Integer leadTimeDays; @NotNull private Currency currency;
    public CreateSupplierMaterialRequest() { }
    public CreateSupplierMaterialRequest(Long supplierId, Long materialId, BigDecimal unitPrice, BigDecimal moq, BigDecimal packageMultiple, Integer leadTimeDays, Currency currency) { this.supplierId = supplierId; this.materialId = materialId; this.unitPrice = unitPrice; this.moq = moq; this.packageMultiple = packageMultiple; this.leadTimeDays = leadTimeDays; this.currency = currency; }
    public Long supplierId() { return supplierId; } public Long materialId() { return materialId; } public BigDecimal unitPrice() { return unitPrice; } public BigDecimal moq() { return moq; } public BigDecimal packageMultiple() { return packageMultiple; } public Integer leadTimeDays() { return leadTimeDays; } public Currency currency() { return currency; }
    public void setSupplierId(Long value) { supplierId = value; } public void setMaterialId(Long value) { materialId = value; } public void setUnitPrice(BigDecimal value) { unitPrice = value; } public void setMoq(BigDecimal value) { moq = value; } public void setPackageMultiple(BigDecimal value) { packageMultiple = value; } public void setLeadTimeDays(Integer value) { leadTimeDays = value; } public void setCurrency(Currency value) { currency = value; }
    @JsonAnySetter public void rejectUnknownField(String name, Object value) { throw new IllegalArgumentException("Property not allowed in request: " + name); }
}
