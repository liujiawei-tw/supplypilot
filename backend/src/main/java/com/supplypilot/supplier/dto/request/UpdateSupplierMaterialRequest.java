package com.supplypilot.supplier.dto.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.supplypilot.supplier.domain.SupplierMaterialStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;

public class UpdateSupplierMaterialRequest {
    @DecimalMin("0.0") private BigDecimal unitPrice; @DecimalMin(value = "0.0", inclusive = false) private BigDecimal moq;
    @DecimalMin(value = "0.0", inclusive = false) private BigDecimal packageMultiple; @Min(0) private Integer leadTimeDays;
    private SupplierMaterialStatus status;
    public UpdateSupplierMaterialRequest() { }
    public UpdateSupplierMaterialRequest(BigDecimal unitPrice, BigDecimal moq, BigDecimal packageMultiple, Integer leadTimeDays, SupplierMaterialStatus status) { this.unitPrice = unitPrice; this.moq = moq; this.packageMultiple = packageMultiple; this.leadTimeDays = leadTimeDays; this.status = status; }
    public BigDecimal unitPrice() { return unitPrice; } public BigDecimal moq() { return moq; } public BigDecimal packageMultiple() { return packageMultiple; } public Integer leadTimeDays() { return leadTimeDays; } public SupplierMaterialStatus status() { return status; }
    public void setUnitPrice(BigDecimal value) { unitPrice = value; } public void setMoq(BigDecimal value) { moq = value; } public void setPackageMultiple(BigDecimal value) { packageMultiple = value; } public void setLeadTimeDays(Integer value) { leadTimeDays = value; } public void setStatus(SupplierMaterialStatus value) { status = value; }
    @JsonAnySetter public void rejectUnknownField(String name, Object value) { throw new IllegalArgumentException("Property not allowed in request: " + name); }
}
