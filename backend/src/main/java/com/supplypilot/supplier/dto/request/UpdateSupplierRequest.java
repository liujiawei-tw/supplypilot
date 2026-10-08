package com.supplypilot.supplier.dto.request;

import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.supplypilot.supplier.domain.SupplierStatus;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class UpdateSupplierRequest {
    @Pattern(regexp = ".*\\S.*") @Size(max = 100) private String name;
    @Size(max = 100) private String country;
    @DecimalMin("0.0") @DecimalMax("100.0") private BigDecimal reliabilityScore;
    @DecimalMin("0.0") @DecimalMax("100.0") private BigDecimal qualityScore;
    private SupplierStatus status;
    public UpdateSupplierRequest() { }
    public UpdateSupplierRequest(String name, String country, BigDecimal reliabilityScore, BigDecimal qualityScore, SupplierStatus status) {
        this.name=name; this.country=country; this.reliabilityScore=reliabilityScore; this.qualityScore=qualityScore; this.status=status;
    }
    @JsonSetter("name") public void setName(String value) { name=value; }
    @JsonSetter("country") public void setCountry(String value) { country=value; }
    @JsonSetter("reliabilityScore") public void setReliabilityScore(BigDecimal value) { reliabilityScore=value; }
    @JsonSetter("qualityScore") public void setQualityScore(BigDecimal value) { qualityScore=value; }
    @JsonSetter("status") public void setStatus(SupplierStatus value) { status=value; }
    @JsonAnySetter public void rejectUnknownField(String name, Object value) { throw new IllegalArgumentException("Property not allowed in request: " + name); }
    public String name() { return name; } public String country() { return country; }
    public BigDecimal reliabilityScore() { return reliabilityScore; } public BigDecimal qualityScore() { return qualityScore; }
    public SupplierStatus status() { return status; }
}
