package com.supplypilot.material.dto.request;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.supplypilot.material.domain.BaseUnit;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public class CreateMaterialRequest {

    @NotBlank(message = "Code is required.")
    @Size(max = 50, message = "Code must not exceed 50 characters.")
    private String code;

    @NotBlank(message = "Name is required.")
    @Size(max = 100, message = "Name must not exceed 100 characters.")
    private String name;

    @NotNull(message = "Base unit is required.")
    private BaseUnit baseUnit;

    @NotNull(message = "Safety stock is required.")
    @PositiveOrZero(message = "Safety stock must be zero or positive.")
    private BigDecimal safetyStock;

    public CreateMaterialRequest() {
    }

    public CreateMaterialRequest(String code, String name, BaseUnit baseUnit, BigDecimal safetyStock) {
        this.code = code;
        this.name = name;
        this.baseUnit = baseUnit;
        this.safetyStock = safetyStock;
    }

    @JsonSetter("code") public void setCode(String code) { this.code = code; }
    @JsonSetter("name") public void setName(String name) { this.name = name; }
    @JsonSetter("baseUnit") public void setBaseUnit(BaseUnit baseUnit) { this.baseUnit = baseUnit; }
    @JsonSetter("safetyStock") public void setSafetyStock(BigDecimal safetyStock) { this.safetyStock = safetyStock; }
    @JsonAnySetter public void rejectUnknownField(String name, Object value) {
        throw new IllegalArgumentException("Property not allowed in request: " + name);
    }

    public String code() { return code; }
    public String name() { return name; }
    public BaseUnit baseUnit() { return baseUnit; }
    public BigDecimal safetyStock() { return safetyStock; }
}
