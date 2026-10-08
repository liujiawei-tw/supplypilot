package com.supplypilot.supplier.dto.request;

import java.math.BigDecimal;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public class CreateSupplierRequest {
    @NotBlank @Size(max = 50) private String code;
    @NotBlank @Size(max = 100) private String name;
    @Size(max = 100) private String country;
    @NotNull @DecimalMin("0.0") @DecimalMax("100.0") private BigDecimal reliabilityScore;
    @NotNull @DecimalMin("0.0") @DecimalMax("100.0") private BigDecimal qualityScore;
    public CreateSupplierRequest() { }
    public CreateSupplierRequest(String code, String name, String country, BigDecimal reliabilityScore, BigDecimal qualityScore) {
        this.code=code; this.name=name; this.country=country; this.reliabilityScore=reliabilityScore; this.qualityScore=qualityScore;
    }
    @JsonSetter("code") public void setCode(String value) { code=value; }
    @JsonSetter("name") public void setName(String value) { name=value; }
    @JsonSetter("country") public void setCountry(String value) { country=value; }
    @JsonSetter("reliabilityScore") public void setReliabilityScore(BigDecimal value) { reliabilityScore=value; }
    @JsonSetter("qualityScore") public void setQualityScore(BigDecimal value) { qualityScore=value; }
    @JsonAnySetter public void rejectUnknownField(String name, Object value) { throw new IllegalArgumentException("Property not allowed in request: " + name); }
    public String code() { return code; } public String name() { return name; } public String country() { return country; }
    public BigDecimal reliabilityScore() { return reliabilityScore; } public BigDecimal qualityScore() { return qualityScore; }
}
